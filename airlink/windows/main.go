// AirLink receiver (Windows/Linux/macOS). Stdlib only.
// Stage 1: mDNS discovery, PIN pairing, encrypted channel, screen mirroring (MJPEG shown in browser).
package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"runtime"
	"sync"
	"time"
)

const (
	tcpPort  = 7000
	httpAddr = "127.0.0.1:7080"
	svcName  = "_airlink._tcp.local"
)

var (
	mu    sync.Mutex
	cond  = sync.NewCond(&mu)
	pin   string
	fails int
	frame []byte
	ver   int
)

func newPin() { // call with mu held
	n, _ := rand.Int(rand.Reader, big.NewInt(1000000))
	pin = fmt.Sprintf("%06d", n)
	fails = 0
	fmt.Printf("\n  Pairing PIN: %s\n\n", pin)
}

func publish(b []byte) {
	mu.Lock()
	frame = b
	ver++
	mu.Unlock()
	cond.Broadcast()
}

func mac(key string, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, []byte(key))
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func handle(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	s := make([]byte, 16)
	rand.Read(s)
	c.Write(s)
	buf := make([]byte, 48)
	if _, err := io.ReadFull(c, buf); err != nil {
		return
	}
	cn := buf[:16]
	mu.Lock()
	p := pin
	mu.Unlock()
	if !hmac.Equal(buf[16:], mac(p, []byte("client"), s, cn)) {
		mu.Lock()
		fails++
		fmt.Println("  Wrong PIN attempt from", c.RemoteAddr())
		if fails >= 3 {
			newPin()
		}
		mu.Unlock()
		return
	}
	c.Write(mac(p, []byte("server"), s, cn))
	key := sha256.Sum256(append(append([]byte(p), s...), cn...))
	blk, _ := aes.NewCipher(key[:])
	gcm, _ := cipher.NewGCM(blk)
	fmt.Println("  Paired with", c.RemoteAddr())
	openBrowser()
	var ctr uint64
	var hdr [4]byte
	for {
		c.SetReadDeadline(time.Now().Add(30 * time.Second))
		if _, err := io.ReadFull(c, hdr[:]); err != nil {
			break
		}
		n := binary.BigEndian.Uint32(hdr[:])
		if n < 17 || n > 16<<20 {
			break
		}
		ct := make([]byte, n)
		if _, err := io.ReadFull(c, ct); err != nil {
			break
		}
		nonce := make([]byte, 12)
		binary.BigEndian.PutUint64(nonce[4:], ctr)
		ctr++
		pt, err := gcm.Open(nil, nonce, ct, nil)
		if err != nil || len(pt) == 0 {
			break
		}
		switch pt[0] {
		case 0:
			fmt.Printf("  Device: %s\n", pt[1:])
		case 1:
			publish(pt[1:])
		}
	}
	fmt.Println("  Disconnected", c.RemoteAddr())
}

const page = `<!doctype html><title>AirLink</title><body style="margin:0;background:#000;height:100vh;display:flex;align-items:center;justify-content:center">
<img src="/stream" style="max-width:100vw;max-height:100vh" ondblclick="document.documentElement.requestFullscreen()">
<p style="position:fixed;top:8px;left:12px;color:#777;font:13px sans-serif">AirLink - double-click for fullscreen</p>`

func stream(w http.ResponseWriter, r *http.Request) {
	fl := w.(http.Flusher)
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=frame")
	w.WriteHeader(200)
	fl.Flush()
	seen := -1
	for r.Context().Err() == nil {
		mu.Lock()
		for ver == seen && r.Context().Err() == nil {
			cond.Wait()
		}
		f := frame
		seen = ver
		mu.Unlock()
		if f == nil {
			continue
		}
		fmt.Fprintf(w, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", len(f))
		w.Write(f)
		w.Write([]byte("\r\n"))
		fl.Flush()
	}
}

func openBrowser() {
	u := "http://" + httpAddr
	switch runtime.GOOS {
	case "windows":
		exec.Command("rundll32", "url.dll,FileProtocolHandler", u).Start()
	case "darwin":
		exec.Command("open", u).Start()
	default:
		exec.Command("xdg-open", u).Start()
	}
}

// ---- minimal mDNS responder (DNS-SD) ----
func dnsName(s string) []byte {
	var b []byte
	for _, p := range bytes.Split([]byte(s), []byte(".")) {
		b = append(b, byte(len(p)))
		b = append(b, p...)
	}
	return append(b, 0)
}

func rr(name string, typ, class uint16, data []byte) []byte {
	b := dnsName(name)
	b = binary.BigEndian.AppendUint16(b, typ)
	b = binary.BigEndian.AppendUint16(b, class)
	b = binary.BigEndian.AppendUint32(b, 120)
	b = binary.BigEndian.AppendUint16(b, uint16(len(data)))
	return append(b, data...)
}

func mdnsReply(host string, ip net.IP) []byte {
	inst, h := host+"."+svcName, host+".local"
	srv := []byte{0, 0, 0, 0}
	srv = binary.BigEndian.AppendUint16(srv, tcpPort)
	srv = append(srv, dnsName(h)...)
	pkt := []byte{0, 0, 0x84, 0, 0, 0, 0, 4, 0, 0, 0, 0}
	pkt = append(pkt, rr(svcName, 12, 1, dnsName(inst))...)
	pkt = append(pkt, rr(inst, 33, 0x8001, srv)...)
	pkt = append(pkt, rr(inst, 16, 0x8001, []byte{0})...)
	return append(pkt, rr(h, 1, 0x8001, ip.To4())...)
}

func ipv4(i net.Interface) net.IP {
	as, _ := i.Addrs()
	for _, a := range as {
		if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil {
			return n.IP
		}
	}
	return nil
}

func mdns(host string) {
	grp := &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: 5353}
	ifs, _ := net.Interfaces()
	for _, ifc := range ifs {
		ifc := ifc
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagMulticast == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		ip := ipv4(ifc)
		if ip == nil {
			continue
		}
		c, err := net.ListenMulticastUDP("udp4", &ifc, grp)
		if err != nil {
			continue
		}
		fmt.Printf("  Address: %s:%d\n", ip, tcpPort)
		go func() {
			reply := mdnsReply(host, ip)
			c.WriteToUDP(reply, grp)
			buf := make([]byte, 1500)
			for {
				n, _, err := c.ReadFromUDP(buf)
				if err != nil {
					return
				}
				if n > 12 && buf[2]&0x80 == 0 && bytes.Contains(bytes.ToLower(buf[:n]), []byte("\x08_airlink\x04_tcp\x05local")) {
					c.WriteToUDP(reply, grp)
				}
			}
		}()
	}
}

func main() {
	host, _ := os.Hostname()
	host = regexp.MustCompile(`[^A-Za-z0-9-]`).ReplaceAllString(host, "-")
	fmt.Println("AirLink receiver - waiting for your phone")
	mu.Lock()
	newPin()
	mu.Unlock()
	mdns(host)
	go func() { // wake stream handlers so they notice closed browsers
		for range time.Tick(time.Second) {
			cond.Broadcast()
		}
	}()
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, page) })
	http.HandleFunc("/stream", stream)
	go http.ListenAndServe(httpAddr, nil)
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", tcpPort))
	if err != nil {
		fmt.Println("Cannot listen:", err)
		time.Sleep(10 * time.Second)
		return
	}
	for {
		c, err := ln.Accept()
		if err != nil {
			continue
		}
		go handle(c)
	}
}

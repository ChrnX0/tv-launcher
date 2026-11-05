// Túnel da TV para a internet (opção E do estudo, 05/11/2026).
//
// Roda o Tailscale INTEIRO dentro deste programa (biblioteca tsnet: rede em "userspace", sem root e sem usar a vaga
// de VPN do Android, que é do AdGuard VPN) e publica, pelo Tailscale Funnel, um único endereço:
//
//	https://<nome>.<sua-rede>.ts.net  ->  http://127.0.0.1:<porta do MCP do launcher>
//
// Só isso: não abre o painel (:8080), não roteia mais nada, não aceita conexão da rede Tailscale (FunnelOnly).
// O launcher (TunelTailscale.kt) sobe este programa, lê a saída e o religa se cair.
//
// Variáveis de ambiente (vindas do launcher):
//
//	TUNEL_DIR      pasta do estado (chave do nó; fica nos dados do launcher)
//	TUNEL_NOME     nome do aparelho na rede Tailscale (ex.: tv-sala)
//	TUNEL_ALVO     para onde repassar (ex.: 127.0.0.1:8091)
//	TUNEL_DNS      servidor DNS para resolver o Tailscale (o Android não tem /etc/resolv.conf)
//	TS_AUTHKEY     chave de autenticação; só no 1º login (depois o estado basta)
//	SSL_CERT_DIR   /system/etc/security/cacerts (os certificados do Android)
//
// Linhas que o launcher lê na saída: "NOME <host.ts.net>" quando o Funnel está no ar, "ERRO <texto>" em falha.
package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"time"

	"tailscale.com/tsnet"
)

func main() {
	dir := os.Getenv("TUNEL_DIR")
	nome := os.Getenv("TUNEL_NOME")
	alvo := os.Getenv("TUNEL_ALVO")
	if dir == "" || nome == "" || alvo == "" {
		fmt.Println("ERRO faltam TUNEL_DIR, TUNEL_NOME ou TUNEL_ALVO")
		os.Exit(2)
	}
	// Só repassa para a própria TV: nada de usar o túnel para alcançar outro endereço.
	if !strings.HasPrefix(alvo, "127.0.0.1:") {
		fmt.Println("ERRO TUNEL_ALVO precisa ser 127.0.0.1:<porta>")
		os.Exit(2)
	}

	// O Android não tem /etc/resolv.conf: sem isto o Go perguntaria a 127.0.0.1:53 e nada resolveria.
	dns := os.Getenv("TUNEL_DNS")
	if dns == "" {
		dns = "1.1.1.1"
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			return d.DialContext(ctx, network, net.JoinHostPort(dns, "53"))
		},
	}

	s := &tsnet.Server{
		Dir:       dir,
		Hostname:  nome,
		AuthKey:   os.Getenv("TS_AUTHKEY"),
		Ephemeral: false,
		Logf:      func(string, ...any) {}, // o log do tsnet é enorme; erros importantes saem abaixo
	}
	defer s.Close()

	ctx, cancelar := context.WithTimeout(context.Background(), 2*time.Minute)
	estado, err := s.Up(ctx)
	cancelar()
	if err != nil {
		fmt.Println("ERRO login no Tailscale:", err)
		os.Exit(3)
	}

	ln, err := s.ListenFunnel("tcp", ":443", tsnet.FunnelOnly())
	if err != nil {
		fmt.Println("ERRO Funnel (ligue HTTPS e Funnel no console do Tailscale):", err)
		os.Exit(4)
	}
	host := ""
	if estado.Self != nil {
		host = strings.TrimSuffix(estado.Self.DNSName, ".")
	}
	fmt.Println("NOME", host)

	destino, _ := url.Parse("http://" + alvo)
	proxy := httputil.NewSingleHostReverseProxy(destino)
	original := proxy.Director
	proxy.Director = func(r *http.Request) {
		host := r.Host // mantém o nome público: o launcher monta os endereços do OAuth com ele
		original(r)
		r.Host = host
	}
	srv := &http.Server{
		Handler:           http.MaxBytesHandler(proxy, 64<<10),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}
	if err := srv.Serve(ln); err != nil {
		fmt.Println("ERRO servidor:", err)
		os.Exit(5)
	}
}

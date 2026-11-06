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
//	TS_AUTHKEY     chave de autenticação (opcional; sem ela o login é pelo QR code: linha LOGIN)
//	SSL_CERT_DIR   /system/etc/security/cacerts (os certificados do Android)
//	TUNEL_PAINEL   opcional (opção "rede Tailscale"): <IP da TV na rede de casa>:8080. Com ela, o painel abre também
//	               pelos aparelhos da conta Tailscale (http://<nome>:8080, só na rede Tailscale, nunca pelo Funnel)
//	               e a lista desses aparelhos sai na linha PARES.
//	TUNEL_ATALHOS  opcional (idem): "8096=nas:8096,..." abre 127.0.0.1:8096 NA TV e repassa, pela rede Tailscale, para
//	               a porta 8096 do aparelho "nas" da conta (app de mídia alcançando um servidor que está em outro lugar).
//
// Linhas que o launcher lê na saída (uma por linha, sempre com prefixo):
//
//	LOGIN <url>    falta entrar no Tailscale: a TV mostra esta URL como QR code (o celular abre e a pessoa entra)
//	ATIVAR <url>   falta ligar HTTPS/Funnel na conta: idem, QR code (a página do Tailscale liga com um clique)
//	CONTA <email>  a conta em que a TV entrou
//	NOME <host>    o Funnel está no ar em https://<host>
//	PARES <json>   (só com TUNEL_PAINEL) aparelhos da conta: [{"nome","so","online","ip"}], a cada minuto
//	ERRO <texto>   falhou (o launcher religa depois)
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"tailscale.com/client/local"
	// Android: sem netlink nem /proc/net para app comum; este pacote dá a lista de interfaces por outro caminho
	// (e DNS e certificados do Android). Sem ele: "netlinkrib: permission denied" (medido na Sala, 06/11/2026).
	_ "tailscale.com/feature/androidbin"
	// Saída pelo proxy do ambiente (HTTPS_PROXY = a ponte Java do launcher, PonteTunel.kt): sem isto o tsnet ignora o proxy.
	_ "tailscale.com/feature/useproxy"
	"tailscale.com/tailcfg"
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
	// O launcher lê este número para encerrar um túnel antigo que tenha sobrado (dois túneis com o mesmo estado brigam).
	_ = os.WriteFile(filepath.Join(dir, "tunel.pid"), []byte(strconv.Itoa(os.Getpid())), 0o600)

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

	// Sem chave, o login é interativo: dá tempo de a pessoa pegar o celular.
	limite := 2 * time.Minute
	if s.AuthKey == "" {
		limite = 20 * time.Minute
	}
	ctx, cancelar := context.WithTimeout(context.Background(), limite)
	defer cancelar()
	if err := s.Start(); err != nil {
		fmt.Println("ERRO iniciar:", err)
		os.Exit(3)
	}
	lc, err := s.LocalClient()
	if err != nil {
		fmt.Println("ERRO iniciar:", err)
		os.Exit(3)
	}
	go mostrarLogin(ctx, lc)
	estado, err := s.Up(ctx)
	if err != nil {
		fmt.Println("ERRO login no Tailscale:", err)
		os.Exit(3)
	}
	if estado.Self != nil {
		if u, ok := estado.User[estado.Self.UserID]; ok && u.LoginName != "" {
			fmt.Println("CONTA", u.LoginName)
		}
	}

	// HTTPS e Funnel precisam estar ligados na conta. Conta nova: o Tailscale dá uma página que liga com um clique.
	if err := esperarFunnel(ctx, lc); err != nil {
		fmt.Println("ERRO Funnel:", err)
		os.Exit(4)
	}

	ln, err := s.ListenFunnel("tcp", ":443", tsnet.FunnelOnly())
	if err != nil {
		fmt.Println("ERRO Funnel (ligue HTTPS e Funnel no console do Tailscale):", err)
		os.Exit(4)
	}
	estado, _ = lc.StatusWithoutPeers(ctx)
	host := ""
	if estado != nil && estado.Self != nil {
		host = strings.TrimSuffix(estado.Self.DNSName, ".")
	}
	fmt.Println("NOME", host)

	if painel := os.Getenv("TUNEL_PAINEL"); painel != "" {
		if err := abrirPainel(s, painel); err != nil {
			fmt.Println("ERRO painel pela rede Tailscale:", err)
		} else {
			go listarPares(lc)
		}
	}
	if atalhos := os.Getenv("TUNEL_ATALHOS"); atalhos != "" {
		for _, a := range strings.Split(atalhos, ",") {
			if err := abrirAtalho(s, a); err != nil {
				fmt.Println("ERRO atalho", a+":", err)
			}
		}
	}

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

// Enquanto falta login, imprime a URL do Tailscale (muda de vez em quando: imprime de novo quando muda).
func mostrarLogin(ctx context.Context, lc *local.Client) {
	pediu := false
	ultima := ""
	for ctx.Err() == nil {
		st, err := lc.StatusWithoutPeers(ctx)
		if err == nil {
			switch st.BackendState {
			case "Running":
				return
			case "NeedsLogin":
				if st.AuthURL == "" && !pediu {
					pediu = true
					_ = lc.StartLoginInteractive(ctx)
				}
				if st.AuthURL != "" && st.AuthURL != ultima {
					ultima = st.AuthURL
					fmt.Println("LOGIN", st.AuthURL)
				}
			}
		}
		time.Sleep(2 * time.Second)
	}
}

// Espera HTTPS + Funnel na conta. Se faltar, mostra (ATIVAR) a página do Tailscale que liga os dois.
func esperarFunnel(ctx context.Context, lc *local.Client) error {
	tem := func() bool {
		st, err := lc.StatusWithoutPeers(ctx)
		return err == nil && st.Self != nil && st.Self.HasCap(tailcfg.CapabilityHTTPS) && st.Self.HasCap(tailcfg.NodeAttrFunnel)
	}
	if tem() {
		return nil
	}
	if info, err := lc.QueryFeature(ctx, "funnel"); err == nil {
		if info.Complete {
			return nil
		}
		if info.URL != "" {
			fmt.Println("ATIVAR", info.URL)
		}
	}
	for ctx.Err() == nil {
		time.Sleep(3 * time.Second)
		if tem() {
			return nil
		}
	}
	return ctx.Err()
}

// Painel pela rede Tailscale: escuta :8080 SÓ na rede Tailscale (Listen, não ListenFunnel) e repassa ao painel no IP
// de casa da TV (o painel só aceita conexão da rede local; daqui ela chega vinda do próprio IP da TV).
func abrirPainel(s *tsnet.Server, alvo string) error {
	h, porta, err := net.SplitHostPort(alvo)
	ip := net.ParseIP(h)
	if err != nil || ip == nil || !ip.IsPrivate() || porta != "8080" {
		return fmt.Errorf("TUNEL_PAINEL inválido")
	}
	ln, err := s.Listen("tcp", ":8080")
	if err != nil {
		return err
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				d := net.Dialer{Timeout: 5 * time.Second}
				t, err := d.Dial("tcp", alvo)
				if err != nil {
					return
				}
				defer t.Close()
				go func() { _, _ = io.Copy(t, c); t.Close() }()
				_, _ = io.Copy(c, t)
			}()
		}
	}()
	return nil
}

type par struct {
	Nome   string `json:"nome"`
	SO     string `json:"so"`
	Online bool   `json:"online"`
	IP     string `json:"ip"`
}

// Aparelhos da conta Tailscale (só leitura), uma linha PARES por minuto.
func listarPares(lc *local.Client) {
	for {
		ctx, cancelar := context.WithTimeout(context.Background(), 10*time.Second)
		st, err := lc.Status(ctx)
		cancelar()
		if err == nil {
			lista := []par{}
			for _, p := range st.Peer {
				ip := ""
				if len(p.TailscaleIPs) > 0 {
					ip = p.TailscaleIPs[0].String()
				}
				lista = append(lista, par{Nome: p.HostName, SO: p.OS, Online: p.Online, IP: ip})
			}
			if b, err := json.Marshal(lista); err == nil {
				fmt.Println("PARES", string(b))
			}
		}
		time.Sleep(time.Minute)
	}
}

// Atalho "local=aparelho:porta": escuta 127.0.0.1:local (só na própria TV) e repassa pela rede Tailscale.
func abrirAtalho(s *tsnet.Server, a string) error {
	local, destino, ok := strings.Cut(a, "=")
	if !ok {
		return fmt.Errorf("formato")
	}
	if _, _, err := net.SplitHostPort(destino); err != nil {
		return err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:"+local)
	if err != nil {
		return err
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				ctx, cancelar := context.WithTimeout(context.Background(), 10*time.Second)
				t, err := s.Dial(ctx, "tcp", destino)
				cancelar()
				if err != nil {
					return
				}
				defer t.Close()
				go func() { _, _ = io.Copy(t, c); t.Close() }()
				_, _ = io.Copy(c, t)
			}()
		}
	}()
	return nil
}

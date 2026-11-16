package main

import (
	"context"
	"net"
	"os"
	"time"
)

// setupDNS makes host lookups work. Go's own resolver reads /etc/resolv.conf,
// which Android doesn't have, and without cgo there is no system resolver
// (golang.org/issue/10714). The app passes the active network's DNS servers
// in CARRYOVER_DNS (comma-separated); queries go to those, in turn.
func setupDNS() {
	servers := dnsServers(os.Getenv("CARRYOVER_DNS"))
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			var err error
			for _, s := range servers {
				var conn net.Conn
				if conn, err = d.DialContext(ctx, network, s); err == nil {
					return conn, nil
				}
			}
			return nil, err
		},
	}
}

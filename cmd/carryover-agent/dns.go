package main

import (
	"net"
	"strings"
)

// fallbackDNS is used when the app passes no DNS servers.
var fallbackDNS = []string{"1.1.1.1", "8.8.8.8"}

// dnsServers parses CARRYOVER_DNS into host:port addresses.
func dnsServers(env string) []string {
	var out []string
	for s := range strings.SplitSeq(env, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		// An IPv6 link-local address may carry a zone ("fe80::1%wlan0").
		if net.ParseIP(strings.SplitN(s, "%", 2)[0]) == nil {
			continue
		}
		out = append(out, net.JoinHostPort(s, "53"))
	}
	if len(out) == 0 {
		for _, s := range fallbackDNS {
			out = append(out, net.JoinHostPort(s, "53"))
		}
	}
	return out
}

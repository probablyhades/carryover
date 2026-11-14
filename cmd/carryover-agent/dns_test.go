package main

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestDNSServers(t *testing.T) {
	assert.Equal(t, []string{"192.168.1.1:53", "[fe80::1%wlan0]:53"}, dnsServers(" 192.168.1.1, junk ,fe80::1%wlan0,"))
	assert.Equal(t, []string{"1.1.1.1:53", "8.8.8.8:53"}, dnsServers(""))
}

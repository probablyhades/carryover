//go:build !android

package main

// setupDNS does nothing outside Android, where the system resolver works.
func setupDNS() {}

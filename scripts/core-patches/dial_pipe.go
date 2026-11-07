//go:build windows

package core

import (
	"fmt"
	"net"
	"strconv"

	"github.com/Microsoft/go-winio"
)

func dial(path string) (net.Conn, error) {
	if _, err := strconv.Atoi(path); err == nil {
		return net.Dial("tcp", fmt.Sprintf("127.0.0.1:%s", path))
	}
	return winio.DialPipe(path, nil)
}

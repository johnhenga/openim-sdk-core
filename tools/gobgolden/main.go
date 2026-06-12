// Package main generates golden test vectors for the websocket envelope
// codec (gob + gzip) used by the Kotlin Multiplatform port.
//
// The Go SDK encodes GeneralWsReq/GeneralWsResp with a fresh gob encoder per
// frame (internal/interaction/encoder.go), so every frame is a deterministic,
// self-contained gob stream: [type-definition messages][value message].
// The Kotlin codec replays the constant type-definition prefix and encodes
// the value message itself; these vectors prove byte-identity. Run:
//
//	go run ./tools/gobgolden kmp/core/testdata/gob-golden.txt
//
// Output format (one record per line, '|'-separated):
//
//	PREFIX_REQ|<hex of type-definition prefix for GeneralWsReq>
//	PREFIX_RESP|<hex of type-definition prefix for GeneralWsResp>
//	REQ|<hex full encoding>|reqIdentifier|token|sendID|operationID|msgIncr|<hex data>
//	RESP|<hex full encoding>|reqIdentifier|errCode|errMsg|msgIncr|operationID|<hex data>
//	GZIP|<hex gzip(raw)>|<hex raw>
package main

import (
	"bytes"
	"compress/gzip"
	"encoding/gob"
	"encoding/hex"
	"fmt"
	"os"
	"strings"
)

// Mirrors of internal/interaction/ws_resp_asyn.go (unexported there).
type GeneralWsReq struct {
	ReqIdentifier int    `json:"reqIdentifier"`
	Token         string `json:"token"`
	SendID        string `json:"sendID"`
	OperationID   string `json:"operationID"`
	MsgIncr       string `json:"msgIncr"`
	Data          []byte `json:"data"`
}

type GeneralWsResp struct {
	ReqIdentifier int    `json:"reqIdentifier"`
	ErrCode       int    `json:"errCode"`
	ErrMsg        string `json:"errMsg"`
	MsgIncr       string `json:"msgIncr"`
	OperationID   string `json:"operationID"`
	Data          []byte `json:"data"`
}

func encode(v any) []byte {
	var buf bytes.Buffer
	if err := gob.NewEncoder(&buf).Encode(v); err != nil {
		panic(err)
	}
	return buf.Bytes()
}

// typedefPrefix returns the constant type-definition prefix by encoding the
// same value twice on one encoder: the second Encode emits only the value
// message, so prefix = first - value.
func typedefPrefix(v any) []byte {
	var buf bytes.Buffer
	enc := gob.NewEncoder(&buf)
	if err := enc.Encode(v); err != nil {
		panic(err)
	}
	first := buf.Len()
	if err := enc.Encode(v); err != nil {
		panic(err)
	}
	valueLen := buf.Len() - first
	prefix := make([]byte, first-valueLen)
	copy(prefix, buf.Bytes()[:first-valueLen])
	// Sanity: prefix + value message must equal the standalone encoding.
	if !bytes.Equal(append(append([]byte{}, prefix...), buf.Bytes()[first:]...), buf.Bytes()[:first]) {
		panic("typedef prefix derivation failed: value messages differ")
	}
	return prefix
}

func h(b []byte) string { return hex.EncodeToString(b) }

func main() {
	var out strings.Builder

	out.WriteString("PREFIX_REQ|" + h(typedefPrefix(GeneralWsReq{ReqIdentifier: 1})) + "\n")
	out.WriteString("PREFIX_RESP|" + h(typedefPrefix(GeneralWsResp{ReqIdentifier: 1})) + "\n")

	reqs := []GeneralWsReq{
		{ReqIdentifier: 1001, Token: "tok-abc", SendID: "user1", OperationID: "op-123", MsgIncr: "1", Data: []byte{0x01, 0x02, 0xff}},
		{ReqIdentifier: 2001, Token: "", SendID: "u", OperationID: "", MsgIncr: "42", Data: nil},
		{}, // all zero values
		{ReqIdentifier: 130, Token: strings.Repeat("t", 200), SendID: "sender-with-longer-id", OperationID: "fcad0b9c44e44e9eb35d76a8a51deeef", MsgIncr: "999999", Data: bytes.Repeat([]byte{0xab}, 300)},
		{ReqIdentifier: -5, MsgIncr: "neg"}, // negative int field
	}
	for _, r := range reqs {
		out.WriteString(fmt.Sprintf("REQ|%s|%d|%s|%s|%s|%s|%s\n",
			h(encode(r)), r.ReqIdentifier, r.Token, r.SendID, r.OperationID, r.MsgIncr, h(r.Data)))
	}

	resps := []GeneralWsResp{
		{ReqIdentifier: 1001, ErrCode: 0, ErrMsg: "", MsgIncr: "1", OperationID: "op-123", Data: []byte{0x0a, 0x0b}},
		{ReqIdentifier: 2002, ErrCode: 1004, ErrMsg: "token expired", MsgIncr: "7", OperationID: "op-x", Data: nil},
		{},
		{ReqIdentifier: 2001, ErrCode: -1, ErrMsg: "服务器错误", MsgIncr: "8", OperationID: "op-unicode", Data: bytes.Repeat([]byte{0x00, 0x10}, 100)},
	}
	for _, r := range resps {
		out.WriteString(fmt.Sprintf("RESP|%s|%d|%d|%s|%s|%s|%s\n",
			h(encode(r)), r.ReqIdentifier, r.ErrCode, r.ErrMsg, r.MsgIncr, r.OperationID, h(r.Data)))
	}

	for _, raw := range [][]byte{
		[]byte("hello gzip"),
		encode(resps[0]),
		bytes.Repeat([]byte{0x42}, 1000),
	} {
		var buf bytes.Buffer
		gz := gzip.NewWriter(&buf)
		if _, err := gz.Write(raw); err != nil {
			panic(err)
		}
		if err := gz.Close(); err != nil {
			panic(err)
		}
		out.WriteString("GZIP|" + h(buf.Bytes()) + "|" + h(raw) + "\n")
	}

	if len(os.Args) > 1 {
		if err := os.WriteFile(os.Args[1], []byte(out.String()), 0o644); err != nil {
			panic(err)
		}
		return
	}
	fmt.Print(out.String())
}

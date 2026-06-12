package main

import (
	"encoding/hex"
	"fmt"
	"github.com/openimsdk/protocol/sdkws"
	"google.golang.org/protobuf/proto"
)

func main() {
	req := &sdkws.PullMessageBySeqsReq{
		UserID: "u1",
		SeqRanges: []*sdkws.SeqRange{
			{ConversationID: "si_a_b", Begin: 5, End: 10, Num: 6},
			{ConversationID: "sg_group1", Begin: 1, End: 100, Num: 100},
		},
		Order: sdkws.PullOrder_PullOrderDesc,
	}
	b, err := proto.Marshal(req)
	if err != nil { panic(err) }
	fmt.Println(hex.EncodeToString(b))
}

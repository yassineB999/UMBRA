package main

import (
	"github.com/gogf/gf/v2/os/gctx"

	"synapse-c2/app/api-template/internal/cmd"
)

func main() {
	cmd.Main.Run(gctx.GetInitCtx())
}

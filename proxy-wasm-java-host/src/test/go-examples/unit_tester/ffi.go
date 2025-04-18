// Copyright 2020-2024 Tetrate
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package main

import (
	"github.com/proxy-wasm/proxy-wasm-go-sdk/proxywasm"
	"github.com/proxy-wasm/proxy-wasm-go-sdk/proxywasm/types"
	"github.com/tidwall/gjson"
	"strings"
)

// ffiTests implements types.HttpContext.
type ffiTests struct {
	types.DefaultHttpContext
	contextID     uint32
	pluginContext *pluginContext
}

func (
p *pluginContext) ffiTests(contextID uint32) types.HttpContext {
	return &ffiTests{
		contextID:     contextID,
		pluginContext: p,
	}
}

func (ctx *ffiTests) OnHttpResponseBody(bodySize int, endOfStream bool) types.Action {

	// we need the full response body to call the FFI function
	if !endOfStream {
		// Wait until we see the entire body to replace.
		return types.ActionPause
	}

	funcName := strings.TrimSpace(gjson.Get(ctx.pluginContext.config, "function").Str)
	proxywasm.LogInfof("calling ffi: %s", funcName)

	body, err := proxywasm.GetHttpResponseBody(0, bodySize)
	if err != nil {
		proxywasm.LogErrorf("failed to get response body: %v", err)
		return types.ActionContinue
	}

	result, err := proxywasm.CallForeignFunction(funcName, body)
	if err != nil {
		proxywasm.LogErrorf("failed to call FFI: %v", err)
		return types.ActionContinue
	}

	if err := proxywasm.ReplaceHttpResponseBody([]byte(result)); err != nil {
		proxywasm.LogErrorf("failed to replace the response: %v", err)
		return types.ActionContinue
	}

	return types.ActionContinue
}

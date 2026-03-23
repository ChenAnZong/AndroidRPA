package com.tencent.yyds;

interface IHandle {
    String http(in String uri, in Map<String, String> params);
}
package com.github.shikejing.netcodelagcompensationjava.web;

public record InputRequest(long sequence, long clientTimeNanos, int moveX, int moveY, boolean fire) { }

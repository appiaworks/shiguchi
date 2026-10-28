package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Message;

/**
 * Unary REST dispatch result: the response pre-serialised to UTF-8 JSON bytes (ready to wrap
 * into a DataBuffer) plus the decoded message so header-level metadata (pagination) can be
 * read without re-parsing the JSON. The message is a generated stub instance when Tier 1
 * served the response, a DynamicMessage on the dynamic path, and null when Tier 0 transcoded
 * the wire bytes directly (only chosen when nothing needs the decoded message).
 */
public record RestResponse(byte[] body, Message message) {}

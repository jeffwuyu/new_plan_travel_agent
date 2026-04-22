package com.travelagent.agent.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;

public record McpProcessHandle(
        Process process,
        BufferedWriter stdin,
        BufferedReader stdout,
        BufferedReader stderr
) {
}

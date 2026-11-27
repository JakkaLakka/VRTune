package com.vrtune.optimizer;

import java.io.IOException;

/** A connected ADB session that can run shell commands (legacy TCP or Wireless-debugging TLS). */
interface AdbShell {
    String shell(String command) throws IOException;

    boolean isOpen();

    int maxCommandLength();

    void close();
}

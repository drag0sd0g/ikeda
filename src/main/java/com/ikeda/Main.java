package com.ikeda;

import com.ikeda.cli.IkedaCommand;
import picocli.CommandLine;

public final class Main {
    public static void main(String[] args) {
        System.exit(new CommandLine(new IkedaCommand()).execute(args));
    }
}

/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.test.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import com.oracle.truffle.llvm.pipe.CaptureOutput;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.test.options.TestOptions;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class ProcessUtil {

    private static final int BUFFER_SIZE = 1024;
    private static final int PROCESS_WAIT_TIMEOUT = 60 * 1000; // 1min timeout

    /**
     * This class represents the result of a native command executed by the operating system.
     */
    public static final class ProcessResult {

        private final String originalCommand;
        private final String stdErr;
        private final String stdOutput;
        private final int returnValue;

        private ProcessResult(String originalCommand, int returnValue, String stdErr, String stdOutput) {
            this.originalCommand = originalCommand;
            this.returnValue = returnValue;
            this.stdErr = stdErr;
            this.stdOutput = stdOutput;
        }

        /**
         * Gets the Standard error stream of the executed native command.
         *
         * @return <code>stderr</code> as a string
         */
        public String getStdErr() {
            return stdErr;
        }

        /**
         * Gets the Standard output stream of the executed native command.
         *
         * @return <code>stdout</code> as a string
         */
        public String getStdOutput() {
            return stdOutput;
        }

        /**
         * Gets the return value of the executed native command.
         *
         * @return <code>stderr</code> as a string
         */
        public int getReturnValue() {
            return returnValue;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("command : " + originalCommand + "\n");
            sb.append("stderr: " + stdErr + "\n");
            sb.append("stdout: " + stdOutput + "\n");
            sb.append("return value: " + returnValue + "\n");
            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            // ignore originalCommand, two different commands can still produce the same output
            if (!(obj instanceof ProcessResult)) {
                return false;
            }

            ProcessResult other = (ProcessResult) obj;
            return this.returnValue == other.returnValue &&
                            Objects.equals(this.stdErr, other.stdErr) &&
                            Objects.equals(this.stdOutput, other.stdOutput);
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 97 * hash + Objects.hashCode(this.stdErr);
            hash = 97 * hash + Objects.hashCode(this.stdOutput);
            hash = 97 * hash + this.returnValue;
            return hash;
        }
    }

    public static ProcessResult executeSulongTestMain(File bitcodeFile, String[] args) throws Exception {
        if (TestOptions.TEST_AOT_IMAGE == null) {
            try (CaptureOutput out = new CaptureOutput()) {
                int result = executeMain(bitcodeFile, args);
                System.out.flush();
                System.err.flush();
                return new ProcessResult(bitcodeFile.getName(), result, out.getStdErr(), out.getStdOut());
            }
        } else {
            String aotArgs = TestOptions.TEST_AOT_ARGS == null ? "" : TestOptions.TEST_AOT_ARGS + " ";
            String cmdline = TestOptions.TEST_AOT_IMAGE + " " + aotArgs + bitcodeFile.getAbsolutePath() + " " + concatCommand(args);
            return executeNativeCommand(cmdline);
        }
    }

    private static int executeMain(File file, String[] args) throws Exception {
        org.graalvm.polyglot.Source source = org.graalvm.polyglot.Source.newBuilder(LLVMLanguage.NAME, file).build();
        Context context = Context.newBuilder().arguments(LLVMLanguage.NAME, args).build();
        try {
            Value result = context.eval(source);
            if (!result.canExecute()) {
                throw new LinkageError("No main function found.");
            }
            return result.execute().asInt();
        } finally {
            context.close();
        }
    }

    public static ProcessResult executeNativeCommandZeroReturn(String command) {
        ProcessResult result = executeNativeCommand(command);
        checkNoError(result);
        return result;
    }

    /**
     * Executes a native command and checks that the return value of the process is 0.
     */
    public static ProcessResult executeNativeCommandZeroReturn(String... command) {
        ProcessResult result;
        if (command.length == 1) {
            result = executeNativeCommand(command[0]);
        } else {
            result = executeNativeCommand(concatCommand(command));
        }
        checkNoError(result);
        return result;
    }

    /**
     * Concats a command by introducing whitespaces between the array elements.
     */
    static String concatCommand(Object[] command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.length; i++) {
            if (i != 0) {
                sb.append(" ");
            }
            sb.append(command[i]);
        }
        return sb.toString();
    }

    public static ProcessResult executeNativeCommand(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command is null!");
        }
        try {
            Process process = Runtime.getRuntime().exec(command);
            boolean success = process.waitFor(PROCESS_WAIT_TIMEOUT, TimeUnit.MILLISECONDS);
            String readError = readStreamAndClose(process.getErrorStream());
            String inputStream = readStreamAndClose(process.getInputStream());
            if (!success) {
                throw new AssertionError("timeout running command: " + command);
            }
            int llvmResult = process.exitValue();
            process.destroyForcibly();
            return new ProcessResult(command, llvmResult, readError, inputStream);
        } catch (Exception e) {
            throw new RuntimeException(command + " ", e);
        }
    }

    public static void checkNoError(ProcessResult processResult) {
        if (processResult.getReturnValue() != 0) {
            throw new IllegalStateException(processResult.originalCommand + " exited with value " + processResult.getReturnValue() + " " + processResult.getStdErr());
        }
    }

    public static String readStreamAndClose(InputStream inputStream) throws IOException {
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final byte[] buffer = new byte[BUFFER_SIZE];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        inputStream.close();
        result.close();
        return result.toString();
    }
}

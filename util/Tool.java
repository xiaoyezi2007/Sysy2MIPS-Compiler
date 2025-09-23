package util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;

public class Tool {

    public Boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public Boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    public Boolean isAlpha(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    public void setOutput(String output) throws FileNotFoundException {
        File file = new File(output);
        FileOutputStream fos = new FileOutputStream(file);
        PrintStream ps = new PrintStream(fos);
        System.setOut(ps);
    }
}

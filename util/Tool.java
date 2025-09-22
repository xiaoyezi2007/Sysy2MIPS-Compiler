package util;

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
}

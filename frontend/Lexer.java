package frontend;

import util.*;
import util.Error;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Lexer {
    private String input;
    private ArrayList<Token> tokens = new ArrayList<>();
    private Tool tool = new Tool();
    private Error error = null;
    private int line = 1;

    public Lexer(String input, Error error) {
        this.input = input;
        this.error = error;
    }

    public ArrayList<Token> analyse() throws FileNotFoundException {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (tool.isDigit(c)) {
                i = parseNum(i,line);
            }
            else if (c == '\n') {
                line++;
            }
            else if (c == '|') {
                if (i+1 < input.length() && input.charAt(i+1) == '|') {
                    addToken("||",line);
                    i++;
                }
                else {
                    addToken("|",line);
                    error.addError("a",line);
                }
            }
            else if (c == '/') {
                if (i+1 < input.length() && input.charAt(i+1) == '/') {
                    i = parseOneLineNote(i);
                }
                else if (i+1 < input.length() && input.charAt(i+1) == '*') {
                    i = parseMulLineNote(i);
                }
                else addToken("/",line);
            }
            else if (c == '&') {
                if (i+1 < input.length() && input.charAt(i+1) == '&') {
                    addToken("&&",line);
                    i++;
                }
                else {
                    addToken("&",line);
                    error.addError("a",line);
                }
            }
            else if (c == '=' && i+1 < input.length() && input.charAt(i+1) == '=') {
                addToken("==",line);
                i++;
            }
            else if (c == '!' && i+1 < input.length() && input.charAt(i+1) == '=') {
                addToken("!=",line);
                i++;
            }
            else if (c == '<' && i+1 < input.length() && input.charAt(i+1) == '=') {
                addToken("<=",line);
                i++;
            }
            else if (c == '>' && i+1 < input.length() && input.charAt(i+1) == '=') {
                addToken(">=", line);
                i++;
            }
            else if (c == '"') {
                i = parseString(i, line);
                if (i == -1) {
                    tool.setOutput("lexer.txt");
                    System.out.println("Error: invalid string");
                    break;
                }
            }
            else if (tool.isBlank(c)) continue;
            else if (tool.isAlpha(c) || c == '_') {
                i = parseWord(i,line);
            }
            else {
                addToken(""+c,line);
            }
        }

        tool.setOutput("lexer.txt");
        print();

        return tokens;
    }

    private int parseOneLineNote(int i) {
        for(int j = i+1; j < input.length(); j++) {
            if (input.charAt(j) == '\n') {
                line++;
                return j;
            }
        }
        return -1;
    }

    private int parseMulLineNote(int i) {
        for (int j = i+1; j < input.length(); j++) {
            if (input.charAt(j) == '/' && input.charAt(j-1) == '*') {
                return j;
            }
            else if (input.charAt(j) == '\n') {
                line++;
            }
        }
        return -1;
    }

    private int parseNum(int i,int l) {
        int id = i;
        String s = "";
        for (int j = i; j < input.length(); j++) {
            if (tool.isDigit(input.charAt(j))) {
                id = j;
                s += input.charAt(j);
            }
            else break;
        }
        addToken(s,l);
        return id;
    }

    private int parseString(int i,int l) {
        String s = "";
        s += "\"";
        for (int j = i+1; j < input.length(); j++) {
            if (input.charAt(j) == '"') {
                s += input.charAt(j);
                addToken(s,l);
                return j;
            }
            else s += input.charAt(j);
        }
        return -1;
    }

    private int parseWord(int i, int l) {
        String s = "";
        int id = i;
        s += input.charAt(i);
        for (int j = i + 1; j < input.length(); j++) {
            char c = input.charAt(j);
            if (tool.isDigit(c) || c == '_' || tool.isAlpha(c)) {
                id = j;
                s += c;
            }
            else break;
        }
        addToken(s,l);
        return id;
    }

    private void addToken(String token, int l) {
        tokens.add(new Token(token,l));
    }

    private void print() {
        for (Token token : tokens) {
            token.lexerPrint();
        }
    }

}

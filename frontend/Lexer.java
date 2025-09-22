package frontend;

import util.*;
import util.Error;

import java.util.ArrayList;

public class Lexer {
    private String input;
    private ArrayList<String> tokens = new ArrayList<>();
    private Tool tool = new Tool();
    private ArrayList<String> kinds = new ArrayList<>();
    private Error error = new Error();

    public Lexer(String input) {
        this.input = input;
    }

    public void analyse() {
        int line = 1;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (tool.isDigit(c)) {
                i = parseNum(i);
            }
            else if (c == '\n') {
                line++;
            }
            else if (c == '|') {
                if (input.charAt(i+1) == '|') {
                    addToken("||");
                    i++;
                }
                else {
                    addToken("|");
                    error.addError("a",line);
                }
            }
            else if (c == '&') {
                if (input.charAt(i+1) == '&') {
                    addToken("&&");
                    i++;
                }
                else {
                    addToken("&");
                    error.addError("a",line);
                }
            }
            else if (c == '"') {
                i = parseString(i);
                if (i == -1) {
                    System.out.println("Error: invalid string");
                }
            }
            else if (tool.isBlank(c)) continue;
            else if (tool.isAlpha(c) || c == '_') {
                i = parseWord(i);
            }
            else {
                addToken(""+c);
            }
        }
        if (error.isError()) {
            error.printError();
        }
        else {
            print();
        }
    }

    private int parseNum(int i) {
        int id = i;
        String s = "";
        for (int j = i; j < input.length(); j++) {
            if (tool.isDigit(input.charAt(j))) {
                id = j;
                s += input.charAt(j);
            }
            else break;
        }
        addToken(s);
        return id;
    }

    private int parseString(int i) {
        String s = "";
        s += "\"";
        for (int j = i+1; j < input.length(); j++) {
            if (input.charAt(j) == '"') {
                s += input.charAt(j);
                addToken(s);
                return j;
            }
            else s += input.charAt(j);
        }
        return -1;
    }

    private int parseWord(int i) {
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
        addToken(s);
        return id;
    }

    private void addToken(String token) {
        tokens.add(token);
        if (token.equals("const")) kinds.add("CONSTTK");
        else if (token.equals("if")) kinds.add("IFTK");
        else if (token.equals("else")) kinds.add("ELSETK");
        else if (token.equals("for")) kinds.add("FORTK");
        else if (token.equals("while")) kinds.add("WHILETK");
        else if (token.equals("break")) kinds.add("BREAKTK");
        else if (token.equals("return")) kinds.add("RETURNTK");
        else if (token.equals("int")) kinds.add("INTTK");
        else if (token.equals("static")) kinds.add("STATICTK");
        else if (token.equals("void")) kinds.add("VOIDTK");
        else if (token.equals("continue")) kinds.add("CONTINUETK");
        else if (token.equals("main")) kinds.add("MAINTK");
        else if (token.equals("!")) kinds.add("NOT");
        else if (token.equals("(")) kinds.add("LPARENT");
        else if (token.equals(")")) kinds.add("RPARENT");
        else if (token.equals("{")) kinds.add("LBRACE");
        else if (token.equals("}")) kinds.add("RBRACE");
        else if (token.equals("[")) kinds.add("LBRACK");
        else if (token.equals("]")) kinds.add("RBRACK");
        else if (token.equals("||") || token.equals("|")) kinds.add("OR");
        else if (token.equals("&&") || token.equals("&")) kinds.add("AND");
        else if (token.equals("+")) kinds.add("PLUS");
        else if (token.equals("-")) kinds.add("MINU");
        else if (token.equals("*")) kinds.add("MULT");
        else if (token.equals("/")) kinds.add("DIV");
        else if (token.equals("%")) kinds.add("MOD");
        else if (token.equals("==")) kinds.add("EQL");
        else if (token.equals("!=")) kinds.add("NEQ");
        else if (token.equals("<")) kinds.add("LSS");
        else if (token.equals(">")) kinds.add("GRE");
        else if (token.equals("<=")) kinds.add("LEQ");
        else if (token.equals(">=")) kinds.add("GEQ");
        else if (token.equals(";")) kinds.add("SEMICN");
        else if (token.equals(",")) kinds.add("COMMA");
        else if (token.equals("=")) kinds.add("ASSIGN");
        else if (tool.isDigit(token.charAt(0))) kinds.add("INTCON");
        else if (tool.isAlpha(token.charAt(0)) || token.charAt(0) == '_') kinds.add("IDENFR");
        else if (token.charAt(0) == '"') kinds.add("STRCON");
    }

    private void print() {
        for (int i = 0; i < tokens.size(); i++) {
            System.out.println(kinds.get(i) + " " + tokens.get(i));
        }
    }

}

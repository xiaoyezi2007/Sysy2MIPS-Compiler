package frontend;

import util.Tool;

public class Token {
    private String kind;
    private String value;
    private int line;
    private Tool tool = new Tool();

    public Token(String value, int line) {
        this.value = value;
        this.line = line;
        if (value.equals("const")) kind=("CONSTTK");
        else if (value.equals("if")) kind=("IFTK");
        else if (value.equals("else")) kind=("ELSETK");
        else if (value.equals("for")) kind=("FORTK");
        else if (value.equals("while")) kind=("WHILETK");
        else if (value.equals("break")) kind=("BREAKTK");
        else if (value.equals("return")) kind=("RETURNTK");
        else if (value.equals("int")) kind=("INTTK");
        else if (value.equals("static")) kind=("STATICTK");
        else if (value.equals("void")) kind=("VOIDTK");
        else if (value.equals("continue")) kind=("CONTINUETK");
        else if (value.equals("main")) kind=("MAINTK");
        else if (value.equals("printf")) kind=("PRINTFTK");
        else if (value.equals("!")) kind=("NOT");
        else if (value.equals("(")) kind=("LPARENT");
        else if (value.equals(")")) kind=("RPARENT");
        else if (value.equals("{")) kind=("LBRACE");
        else if (value.equals("}")) kind=("RBRACE");
        else if (value.equals("[")) kind=("LBRACK");
        else if (value.equals("]")) kind=("RBRACK");
        else if (value.equals("||") || value.equals("|")) kind=("OR");
        else if (value.equals("&&") || value.equals("&")) kind=("AND");
        else if (value.equals("+")) kind=("PLUS");
        else if (value.equals("-")) kind=("MINU");
        else if (value.equals("*")) kind=("MULT");
        else if (value.equals("/")) kind=("DIV");
        else if (value.equals("%")) kind=("MOD");
        else if (value.equals("==")) kind=("EQL");
        else if (value.equals("!=")) kind=("NEQ");
        else if (value.equals("<")) kind=("LSS");
        else if (value.equals(">")) kind=("GRE");
        else if (value.equals("<=")) kind=("LEQ");
        else if (value.equals(">=")) kind=("GEQ");
        else if (value.equals(";")) kind=("SEMICN");
        else if (value.equals(",")) kind=("COMMA");
        else if (value.equals("=")) kind=("ASSIGN");
        else if (tool.isDigit(value.charAt(0))) kind=("INTCON");
        else if (tool.isAlpha(value.charAt(0)) || value.charAt(0) == '_') kind=("IDENFR");
        else if (value.charAt(0) == '"') kind=("STRCON");
    }

    public void lexerPrint() {
        System.out.println(kind + " " + value);
    }

    public String getKind() {
        return kind;
    }

    public String getValue() {
        return value;
    }

    public int getLine() {
        return line;
    }
}

import frontend.*;
import util.Error;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Compiler {
    static Error error = new Error();

    public static void main(String[] args) throws FileNotFoundException {
        Reader reader = new Reader();
        reader.setInputStream();
        String in = reader.input();
        Lexer lexer = new Lexer(in,error);
        ArrayList<Token> tokens = lexer.analyse();
        Parser parser = new Parser(tokens, error);
        ASTNode ASTRoot = null;
        ASTRoot = parser.analyse();
        Visitor visitor = new Visitor(ASTRoot, error);
        SymbolTable table = visitor.analyse();
        error.printError();
    }
}

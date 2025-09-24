import frontend.*;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Compiler {
    public static void main(String[] args) throws FileNotFoundException {
        Reader reader = new Reader();
        reader.setInputStream();
        String in = reader.input();
        Lexer lexer = new Lexer(in);
        ArrayList<Token> tokens = lexer.analyse();
        Parser parser = new Parser(tokens);
        parser.analyse();
    }
}

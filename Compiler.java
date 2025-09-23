import frontend.*;

import java.io.FileNotFoundException;

public class Compiler {
    public static void main(String[] args) throws FileNotFoundException {
        Reader reader = new Reader();
        reader.setInputStream();
        String in = reader.input();
        Lexer lexer = new Lexer(in);
        lexer.analyse();
    }
}

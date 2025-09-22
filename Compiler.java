import frontend.*;

import java.io.FileNotFoundException;

public class Compiler {
    public static void main(String[] args) {
        Reader reader = new Reader();
        try {
            reader.setInputStream();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        String in = reader.input();
        //in = reader.symplifySpace(in);
        Lexer lexer = new Lexer(in);
        lexer.analyse();
    }
}

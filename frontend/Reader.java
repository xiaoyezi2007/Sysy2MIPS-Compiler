package frontend;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Scanner;

public class Reader {
    public static FileInputStream inputStream = null;
    public static Scanner scanner = null;

    public void setInputStream() throws FileNotFoundException {
        inputStream = new FileInputStream("testfile.txt");
        System.setIn(inputStream);
        scanner = new Scanner(System.in);
    }

    public String input() {
        String in = "";
        while(scanner.hasNextLine()) {
            in += scanner.nextLine();
            in += '\n';
        }
        return in;
    }

    public String symplifySpace(String s) {
        String ans = "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' '||c == '\t'||c=='\n'||c=='\r') {
                if (!ans.isEmpty() && ans.charAt(ans.length()-1) != ' ') {
                    ans += " ";
                }
                else {
                    continue;
                }
            }
            else {
                ans += c;
            }
        }
        return ans;
    }
}

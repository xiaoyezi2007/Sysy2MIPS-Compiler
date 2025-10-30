package llvm;

public class IRType {
    public IRType child = null;
    public String name = "";
    public int size = -1;
    public boolean isAddr = false;

    public IRType(String name) {
        this.name = name;
    }

    public IRType(String name, IRType child) {
        this.name = name;
        this.child = child;
    }

    public IRType(int size) {
        this.size = size;
    }

    public IRType ptTo() {
        return child;
    }

    public boolean equals(String s) {
        return s.equals(name);
    }

    public boolean isArray() {return size != -1;}

    public String toString() {
        if (size != -1) {
            return "["+size+" x i32]";
        }
        else if (name.equals("ptr")) {
            return child.toString()+"*";
        }
        else if (name.equals("int")) {
            return "i32";
        }
        else {
            return name;
        }
    }
}

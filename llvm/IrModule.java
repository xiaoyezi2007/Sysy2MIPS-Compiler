package llvm;

import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class IrModule {
    ArrayList<GlobalValue> globals = new ArrayList<>();
    ArrayList<Function> functions = new ArrayList<>();
    private Tool tool = new Tool();

    public IrModule() {
    }

    public void addFunction(Function function) {
        functions.add(function);
    }

    public void addGlobal(GlobalValue global) {
        globals.add(global);
    }

    public void toMips() {
        for (GlobalValue gv : globals) {
            gv.toMips();
        }
        for (Function f : functions) {
            f.toMips();
        }
    }

    public void print() {
        try {
            tool.setOutput("llvm_ir.txt");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        System.out.println("declare i32 @getint()");
        System.out.println("declare void @putint(i32)");
        System.out.println("declare void @putch(i32)");
        System.out.println("declare void @putstr(i8*)\n");
        for (GlobalValue gv : globals) {
            gv.print();
        }
        System.out.println();
        for (Function function : functions) {
            function.print();
            System.out.println();
        }
    }
}

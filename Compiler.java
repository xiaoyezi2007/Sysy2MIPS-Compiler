import frontend.*;
import frontend.error.eVisitor;
import llvm.*;
import mips.MipsBuilder;
import mips.MipsModule;
import optimizer.LLVMOptimizer;
import optimizer.Optimizer;
import util.Error;
import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Compiler {
    static Error error = new Error();
    static boolean optimized = true;

    private static final boolean PROFILE = Boolean.getBoolean("profile");

    private static long now() {
        return System.nanoTime();
    }

    private static void prof(String name, long startNs) {
        if (!PROFILE) {
            return;
        }
        double ms = (System.nanoTime() - startNs) / 1_000_000.0;
        System.err.printf("[profile] %s: %.3f ms%n", name, ms);
    }

    public static void main(String[] args) throws FileNotFoundException {
        long t0;
        Reader reader = new Reader();
        t0 = now();
        reader.setInputStream();

        String in = reader.input();
        prof("read input", t0);

        t0 = now();
        Lexer lexer = new Lexer(in,error);
        ArrayList<Token> tokens = lexer.analyse();
        prof("lex", t0);

        t0 = now();
        Parser parser = new Parser(tokens, error);
        ASTNode ASTRoot = null;
        ASTRoot = parser.analyse();
        prof("parse", t0);

        t0 = now();
        eVisitor Evisitor = new eVisitor(ASTRoot, error);
        Evisitor.analyse();
        prof("semantic", t0);
        if (error.isError()) {
            error.printError();
            return;
        }

        t0 = now();
        IrModule irModule = new IrModule();
        Builder builder = new Builder(irModule, optimized);
        Visitor visitor = new Visitor(ASTRoot, error);
        SymbolTable table = visitor.analyse();
        prof("build IR", t0);

        Tool tool = new Tool();
        tool.setOutput("llvm_ir.txt");
        irModule.print();

        if (optimized) {
            t0 = now();
            LLVMOptimizer llvmOptimizer = new LLVMOptimizer(irModule);
            irModule = llvmOptimizer.optimize();
            prof("LLVM optimize", t0);

            tool.setOutput("OptLLVM.txt");
            irModule.print();
        }

        MipsBuilder mipsBuilder = new MipsBuilder();
        t0 = now();
        irModule.toMips();
        prof("lower to MIPS (incl. regalloc)", t0);
        MipsModule mipsModule = MipsBuilder.module;

        if (optimized) {
            t0 = now();
            Optimizer optimizer = new Optimizer(mipsModule);
            mipsModule = optimizer.optimize();
            prof("MIPS optimize", t0);
        }

        t0 = now();
        mipsModule.print();
        prof("print MIPS", t0);
    }
}

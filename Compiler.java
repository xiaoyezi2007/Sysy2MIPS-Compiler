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

    public static void main(String[] args) throws FileNotFoundException {
        Reader reader = new Reader();
        reader.setInputStream();

        String in = reader.input();
        Lexer lexer = new Lexer(in,error);
        ArrayList<Token> tokens = lexer.analyse();

        Parser parser = new Parser(tokens, error);
        ASTNode ASTRoot = null;
        ASTRoot = parser.analyse();

        eVisitor Evisitor = new eVisitor(ASTRoot, error);
        Evisitor.analyse();
        if (error.isError()) {
            error.printError();
            return;
        }

        IrModule irModule = new IrModule();
        Builder builder = new Builder(irModule, optimized);
        Visitor visitor = new Visitor(ASTRoot, error);
        SymbolTable table = visitor.analyse();

        Tool tool = new Tool();
        tool.setOutput("llvm_ir.txt");
        irModule.print();

        if (optimized) {
            LLVMOptimizer llvmOptimizer = new LLVMOptimizer(irModule);
            irModule = llvmOptimizer.optimize();

            tool.setOutput("OptLLVM.txt");
            irModule.print();
        }

        MipsBuilder mipsBuilder = new MipsBuilder();
        irModule.toMips();
        MipsModule mipsModule = MipsBuilder.module;

        if (optimized) {
            Optimizer optimizer = new Optimizer(mipsModule);
            mipsModule = optimizer.optimize();
        }

        mipsModule.print();
    }
}

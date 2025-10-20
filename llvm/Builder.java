package llvm;

import llvm.instr.*;

import java.util.ArrayList;

public class Builder {
    private static IrModule irModule = null;
    private static Function curFunction = null;
    private static BasicBlock curBlock = null;
    private static int VarCnt = -1;
    private static int StringCnt = 0;

    public static Function getint = new Function("int", "getint");
    public static Function putstr = new Function("void", "putstr");
    public static Function putint = new Function("void", "putint");
    public static Function putch = new Function("void", "putch");

    public Builder(IrModule irModule) {
        this.irModule = irModule;
    }

    public static void addInstr(Instruction instr) {
        curBlock.addInstruction(instr);
    }

    public static void addBasicBlock(BasicBlock basicBlock) {
        curFunction.addBasicBlock(basicBlock);
        curBlock = basicBlock;
    }

    public static void addFunction(Function function) {
        irModule.addFunction(function);
        curFunction = function;
        addBasicBlock(new BasicBlock());
        VarCnt = -1;
    }

    public static void varCntMinus() {
        VarCnt--;
    }

    public static void addGlobalValue(GlobalValue value) {
        irModule.addGlobal(value);
    }

    public static String getVarName() {
        VarCnt++;
        return "%" + VarCnt;
    }

    public static String getFunctionName() {
        VarCnt++;
        return "#" + VarCnt;
    }

    public static String getStringName() {
        StringCnt++;
        return "@.str." + StringCnt;
    }
}

package llvm;

import llvm.constant.ConstantVoid;
import llvm.instr.*;

import java.util.ArrayList;
import java.util.Stack;

public class Builder {
    private static IrModule irModule = null;
    private static Function curFunction = null;
    private static BasicBlock curBlock = null;
    private static int VarCnt = -1;
    private static int StringCnt = 0;
    private static boolean blockTerminal = false;

    public static Function getint = new Function("int", "getint");
    public static Function putstr = new Function("void", "putstr");
    public static Function putint = new Function("void", "putint");
    public static Function putch = new Function("void", "putch");

    private static Stack<BasicBlock> cycleBlocks = new Stack<>();
    private static Stack<BasicBlock> forStmtBlocks = new Stack<>();
    private static Stack<BasicBlock> endBlocks = new Stack<>();

    public static BasicBlock ifBlock = null;
    public static BasicBlock elseBlock = null;
    public static BasicBlock endBlock = null;

    public Builder(IrModule irModule) {
        this.irModule = irModule;
    }

    public static void addInstr(Instruction instr) {
        if (!blockTerminal) {
            curBlock.addInstruction(instr);
        }
        if (instr instanceof RetInstr || instr instanceof JumpInstr || instr instanceof BranchInstr) {
            blockTerminal = true;
        }
    }

    public static void checkReturn() {
        if (!blockTerminal && !curBlock.isReturn()) {
            addInstr(new RetInstr(new ConstantVoid()));
        }
    }

    public static BasicBlock getBranchBlock(boolean succeed) {
        if (succeed) {
            if (ifBlock == null) {
                return getForBlock("cycle");
            }
            else {
                return getIfBlock("if");
            }
        }
        else {
            if (ifBlock == null) {
                return getForBlock("end");
            }
            else {
                if (elseBlock == null) {
                    return getIfBlock("end");
                }
                else {
                    return getIfBlock("else");
                }
            }
        }
    }

    public static BasicBlock getIfBlock(String type) {
        if (type.equals("if")) {
            return ifBlock;
        }
        else if (type.equals("else")) {
            return elseBlock;
        }
        else if (type.equals("end")) {
            return endBlock;
        }
        return null;
    }

    public static void addForBlocks(BasicBlock cycleBlock, BasicBlock forStmtBlock, BasicBlock endBlock) {
        cycleBlocks.add(cycleBlock);
        forStmtBlocks.add(forStmtBlock);
        endBlocks.add(endBlock);
    }

    public static void popForBlocks() {
        BasicBlock cycleBlock = cycleBlocks.pop();
        BasicBlock forStmtBlock = forStmtBlocks.pop();
        BasicBlock endBlock = endBlocks.pop();
    }

    public static BasicBlock getForBlock(String blockName) {
        if (blockName.equals("cycle")) {
            return cycleBlocks.peek();
        }
        else if (blockName.equals("forStmt")) {
            return forStmtBlocks.peek();
        }
        else if (blockName.equals("end")) {
            return endBlocks.peek();
        }
        return null;
    }

    public static void addBasicBlock(BasicBlock basicBlock) {
        curFunction.addBasicBlock(basicBlock);
        curBlock = basicBlock;
        VarCnt++;
        curBlock.setName(String.valueOf(VarCnt));
        blockTerminal = false;
    }

    public static void addFunction(Function function) {
        irModule.addFunction(function);
        curFunction = function;
        VarCnt = -1;
        blockTerminal = false;
    }

    public static void varCntMinus() {
        VarCnt--;
    }

    public static void addGlobalValue(GlobalValue value) {
        irModule.addGlobal(value);
    }

    public static String getVarName() {
        if (!blockTerminal) {
            VarCnt++;
        }
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

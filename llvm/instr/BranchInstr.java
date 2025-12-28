package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.JInstr;
import mips.Register;

public class BranchInstr extends Instruction {

    public BranchInstr(Value Cond, BasicBlock Block1, BasicBlock Block2) {
        super(ValueType.BRANCH_INST, new IRType("void"), "void");
        addUseValue(Cond);
        addUseValue(Block1);
        addUseValue(Block2);
        Builder.addInstr(this);
    }

    @Override
    public void toMips() {
        Value Cond = getUseValue(0);
        Value Block1 = getUseValue(1);
        Value Block2 = getUseValue(2);
        Register t0 = tmp(0);
        Register creg = valueOrLoad(Cond, t0);
        new IInstr("beq", creg, 1, ((BasicBlock) Block1).getMipsLabel());
        new JInstr("j", ((BasicBlock) Block2).getMipsLabel());
    }

    public BasicBlock getTrueBlock() {
        return (BasicBlock) getUseValue(1);
    }

    public BasicBlock getFalseBlock() {
        return (BasicBlock) getUseValue(2);
    }

    public void print() {
        Value Cond = getUseValue(0);
        Value Block1 = getUseValue(1);
        Value Block2 = getUseValue(2);
        System.out.println("br i1 "+Cond.getName()+", label %"+Block1.getName()+", label %"+Block2.getName());
    }

    @Override
    public boolean isDead() {
        return false;
    }

    public void replaceBlock(BasicBlock from, BasicBlock to) {
        Value Block1 = getUseValue(1);
        if (Block1.equals(from)) {
            setUseValue(1, to);
        }
        Value Block2 = getUseValue(2);
        if (Block2.equals(from)) {
            setUseValue(2, to);
        }
    }

    @Override
    public boolean isPinned() {
        return true;
    }

    @Override
    public boolean isTerminal() {
        return true;
    }
}

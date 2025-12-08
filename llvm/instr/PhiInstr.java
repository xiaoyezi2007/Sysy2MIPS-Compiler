package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.IRType;
import llvm.Value;
import llvm.ValueType;
import mips.MipsBuilder;

import java.util.ArrayList;

public class PhiInstr extends Instruction {
    public AllocaInstr var;
    public BasicBlock block;
    public ArrayList<BasicBlock> defBlock = new ArrayList<>();

    public PhiInstr(AllocaInstr value, BasicBlock block) {
        super(ValueType.PHI_INST, value.getType().ptTo(), Builder.getVarName());
        this.var = value;
        this.block = block;
    }

    public void addMove() {
        for (int i=0; i<defBlock.size(); i++) {
            Value v = getUseValue(i);
            defBlock.get(i).addMove(v,this);
        }
    }

    public void replaceBlock(BasicBlock from, BasicBlock to) {
        for (int i=0; i<defBlock.size(); i++) {
            if (defBlock.get(i).equals(from)) {
                defBlock.set(i, to);
            }
        }
    }

    public void addBlock(BasicBlock block) {
        defBlock.add(block);
    }

    public BasicBlock getBlock() {
        return block;
    }

    public void initPhi(BasicBlock block) {
        var.store(block, this);
    }

    public void updatePhi(BasicBlock block) {
        for (BasicBlock b : block.prev) {
            Value v = var.findDef(b, new ArrayList<>());
            if (v != null) {
                defBlock.add(b);
                addUseValue(v);
            }
        }
    }

    public BasicBlock findPrevBlock(Instruction instr) {
        for (int i=0;i<defBlock.size();i++) {
            Value value = getUseValue(i);
            if (instr.equals(value)) {
                return defBlock.get(i);
            }
        }
        return null;
    }

    public void print() {
        System.out.print(name+" = phi "+getTypeName()+" ");
        boolean flag = false;
        for (int i = 0; i < defBlock.size(); i++) {
            Value value = getUseValue(i);
            if (value != null) {
                if (flag) {
                    System.out.print(", ");
                }
                System.out.print("["+value.getName() + ", %"+defBlock.get(i).getName()+"] ");
                flag = true;
            }
        }
        System.out.println();
    }

    public void toMips() {
        if (memory == 1) {
            memory = MipsBuilder.memory;
            MipsBuilder.memory -= 4;
        }
    }

    @Override
    public boolean isPinned() {
        return true;
    }
}

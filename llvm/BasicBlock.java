package llvm;

import llvm.instr.BranchInstr;
import llvm.instr.Instruction;
import llvm.instr.MoveInstr;
import llvm.instr.PhiInstr;
import llvm.instr.RetInstr;
import mips.Label;

import java.util.ArrayList;
import java.util.HashSet;

public class BasicBlock extends Value {
    private Function fatherFunction;
    ArrayList<Instruction> instructions = new ArrayList<>();

    public HashSet<BasicBlock> next = new HashSet<>();
    public HashSet<BasicBlock> prev = new HashSet<>();
    public HashSet<BasicBlock> dom = new HashSet<>(); // block in array dom this block
    public HashSet<BasicBlock> dominanceFrontier = new HashSet<>();
    public BasicBlock directDom = null;

    public BasicBlock() {
        super(ValueType.BASIC_BLOCK, new IRType("void"), "block");
    }

    public void addMove(Value from, Value to) {
        instructions.add(instructions.size()-1, new MoveInstr(from, to));
    }

    public void replaceNextBlock(BasicBlock from, BasicBlock to) {
        Instruction instr = instructions.get(instructions.size()-1);
        if (instr instanceof BranchInstr) {
            ((BranchInstr) instr).replaceBlock(from, to);
        }
    }

    public void replacePrevBlock(BasicBlock from, BasicBlock to) {
        for (Instruction instr : instructions) {
            if (!(instr instanceof PhiInstr)) {
                break;
            }
            PhiInstr phi = (PhiInstr) instr;
            phi.replaceBlock(from, to);
        }
    }

    public void addDF(BasicBlock b) {
        dominanceFrontier.add(b);
    }

    public void insertPhi(PhiInstr instr) {
        instructions.add(0, instr);
    }

    public boolean updateDom() {
        HashSet<BasicBlock> newDom = new HashSet<>();
        for (BasicBlock block : prev) {
            if (newDom.isEmpty()) {
                newDom.addAll(block.dom);
            }
            else {
                newDom.retainAll(block.dom);
            }
        }
        newDom.add(this);
        if (newDom.equals(dom)) {
            return false;
        }
        dom = newDom;
        return true;
    }

    public void updateDirectDom() {
        if (dom.size() == 1) {
            return;
        }
        for (BasicBlock block : dom) {
            boolean flag = true;
            for (BasicBlock block2 : dom) {
                if (block2.isStrictDominate(block) && !block2.equals(this)) {
                    flag = false;
                    break;
                }
            }
            if (flag && !block.equals(this)) {
                directDom = block;
                break;
            }
        }
    }

    public boolean isDominate(BasicBlock block) {
        return dom.contains(block);
    }

    public boolean isStrictDominate(BasicBlock block) {  // be dominated
        return dom.contains(block) && !block.equals(this);
    }

    public void setFatherFunction(Function fatherFunction) {
        this.fatherFunction = fatherFunction;
    }

    public void addNextBlock(BasicBlock nextBlock) {
        next.add(nextBlock);
    }

    public void addPrevBlock(BasicBlock prevBlock) {
        prev.add(prevBlock);
    }

    public void removeNextBlock(BasicBlock nextBlock) {
        next.remove(nextBlock);
    }

    public void removePrevBlock(BasicBlock prevBlock) {
        prev.remove(prevBlock);
    }

    public void popInstr() {
        instructions.remove(instructions.size() - 1);
    }

    public ArrayList<Instruction> getInstructions() {
        return instructions;
    }

    public void addInstruction(Instruction instruction) {
        instructions.add(instruction);
    }

    public void rmInstruction(int i) {
        instructions.remove(i);
    }

    public void rmInstruction(Instruction instruction) {
        instructions.remove(instruction);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isReturn() {
        return !instructions.isEmpty() && instructions.get(instructions.size()-1) instanceof RetInstr;
    }

    public String getMipsLabel() {
        return fatherFunction.name+"."+getName();
    }

    public int getSpace() {
        int ans = 0;
        for (Instruction instruction : instructions) {
            ans+=instruction.getSpace();
        }
        return ans;
    }

    @Override
    public void toMips() {
        for (Instruction instruction : instructions) {
            instruction.toMips();
        }
    }

    public void print() {
        for (Instruction instruction : instructions) {
            instruction.print();
        }
    }
}

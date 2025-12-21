package optimizer;

import mips.IInstr;
import mips.JInstr;
import mips.Label;
import mips.LswInstr;
import mips.MipsInstr;
import mips.MipsModule;
import mips.MipsString;
import mips.MipsWord;
import mips.Register;
import mips.RInstr;
import mips.SpecialInstr;
import mips.Syscall;
import mips.fake.LaInstr;
import mips.fake.LiInstr;
import mips.fake.MoveInstr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Optimizer {
    private MipsModule mipsModule;
    private ArrayList<MipsInstr> Instrs;
    private ArrayList<MipsWord> Words;
    private ArrayList<MipsString> Strings;

    public Optimizer(MipsModule mipsModule) {
        this.mipsModule = mipsModule;
    }

    public MipsModule optimize() {
        Instrs = mipsModule.getInstrs();
        Words = mipsModule.getWords();
        Strings = mipsModule.getStrings();
        MipsModule optimizedModule = new MipsModule();
        for (MipsWord word : Words) {
            optimizedModule.addWord(word);
        }
        for (MipsString string : Strings) {
            optimizedModule.addString(string);
        }

        //multDivOptimize();
        pesudoInstrOptimize();
        peepholeOptimize();

        for (MipsInstr instr : Instrs) {
            optimizedModule.addInstr(instr);
        }

        return optimizedModule;
    }

    public void multDivOptimize() {
        ArrayList<MipsInstr> optInstrs = new ArrayList<>();
        for (int i = 0; i < Instrs.size(); i++) {
            MipsInstr instr = Instrs.get(i);
            if (instr instanceof IInstr && instr.getOp().equals("mul")) {}
        }
    }

    public void pesudoInstrOptimize() {
        ArrayList<MipsInstr> optInstrs = new ArrayList<>();
        for (int i = 0; i < Instrs.size(); i++) {
            MipsInstr instr = Instrs.get(i);
            if (instr instanceof IInstr && instr.getOp().equals("subi")) {
                IInstr iInstr = (IInstr) instr;
                int x = (iInstr).getImmediate();
                optInstrs.add(new IInstr("addi", iInstr.getRs(), iInstr.getRt(), -x));
            }
            else {
                optInstrs.add(instr);
            }
        }
        Instrs = optInstrs;
    }

    public void peepholeOptimize() {
        // Pass 1: basic-block-local stack-slot peephole (no cross-CFG propagation).
        ArrayList<MipsInstr> out = new ArrayList<>();
        Map<Integer, SlotState> slotStates = new HashMap<>();

        for (MipsInstr instr : Instrs) {
            // Basic block boundary: label.
            if (instr instanceof Label) {
                clearSlotStates(slotStates);
                out.add(instr);
                continue;
            }

            // Hard barriers.
            if (instr instanceof Syscall) {
                clearSlotStates(slotStates);
                out.add(instr);
                clearSlotStates(slotStates);
                continue;
            }
            if (instr instanceof JInstr) {
                clearSlotStates(slotStates);
                out.add(instr);
                clearSlotStates(slotStates);
                continue;
            }
            if (instr instanceof IInstr) {
                IInstr iInstr = (IInstr) instr;
                // Branch-like IInstr (beq/bne/...) ends the block.
                if (iInstr.getRt() == null) {
                    clearSlotStates(slotStates);
                    out.add(instr);
                    clearSlotStates(slotStates);
                    continue;
                }
                // Stack pointer adjustment changes addressing for all $sp slots.
                if (iInstr.getRs() == Register.SP) {
                    clearSlotStates(slotStates);
                    out.add(instr);
                    clearSlotStates(slotStates);
                    continue;
                }
            }

            // Memory ops: only optimize clean word-aligned k($sp) accesses.
            if (instr instanceof LswInstr) {
                LswInstr mem = (LswInstr) instr;

                if (!isEligibleSpSlot(mem)) {
                    clearSlotStates(slotStates);
                    out.add(instr);
                    clearSlotStates(slotStates);
                    continue;
                }

                int slot = mem.getImmediate();
                SlotState state = slotStates.computeIfAbsent(slot, k -> new SlotState());

                if (mem.getOp().equals("lw")) {
                    Register dst = mem.getRs();

                    Register src = state.knownReg;
                    if (src == null) {
                        src = state.lastStoredReg;
                    }

                    if (src != null) {
                        // Replace lw with move (or delete if dst already equals src).
                        if (dst != src) {
                            out.add(new MoveInstr(src, dst, false));
                        }
                        state.readSinceLastStore = true;
                        // Invalidate other cached uses of dst, then record dst for this slot.
                        invalidateByDefinedReg(slotStates, dst);
                        state.knownReg = dst;
                        continue;
                    }

                    // Can't forward: keep the load, but update cache info.
                    out.add(mem);
                    state.readSinceLastStore = true;
                    invalidateByDefinedReg(slotStates, dst);
                    state.knownReg = dst;
                    continue;
                }

                if (mem.getOp().equals("sw")) {
                    // Dead store elimination (within the block):
                    // sw ..., slot; ...; sw ..., slot  and no intervening read from slot.
                    if (!state.readSinceLastStore && state.lastStoreOutIndex >= 0) {
                        out.set(state.lastStoreOutIndex, null);
                    }

                    out.add(mem);
                    state.lastStoreOutIndex = out.size() - 1;
                    state.readSinceLastStore = false;

                    Register src = mem.getRs();
                    state.lastStoredReg = src;
                    state.knownReg = src;
                    continue;
                }

                // Unknown mem op: act as barrier.
                clearSlotStates(slotStates);
                out.add(instr);
                clearSlotStates(slotStates);
                continue;
            }

            // Normal instruction: emit, then invalidate caches based on defined register.
            out.add(instr);
            Register defined = getDefinedRegister(instr);
            if (defined != null) {
                invalidateByDefinedReg(slotStates, defined);
            }
        }

        // Filter nulls from dead-store elimination.
        ArrayList<MipsInstr> pass1 = new ArrayList<>();
        for (MipsInstr instr : out) {
            if (instr != null) {
                pass1.add(instr);
            }
        }

        // Pass 2: j L; L:  => remove j
        ArrayList<MipsInstr> pass2 = new ArrayList<>();
        int i = 0;
        while (i < pass1.size()) {
            MipsInstr now = pass1.get(i);
            if (i < pass1.size() - 1) {
                MipsInstr nxt = pass1.get(i + 1);
                if (now instanceof JInstr && nxt instanceof Label) {
                    JInstr j = (JInstr) now;
                    Label l = (Label) nxt;
                    if (j.getOp().equals("j") && j.getLabel().equals(l.getName())) {
                        i++;
                        continue;
                    }
                }
            }
            pass2.add(now);
            i++;
        }
        Instrs = pass2;
    }

    private boolean isSameSpSlot(LswInstr a, LswInstr b) {
        if (a.isLabelMode() || b.isLabelMode()) {
            return false;
        }
        if (a.getRt() != Register.SP || b.getRt() != Register.SP) {
            return false;
        }
        int offA = a.getImmediate();
        int offB = b.getImmediate();
        if (offA != offB) {
            return false;
        }
        // Avoid touching saved $ra slot.
        if (offA == 0) {
            return false;
        }
        // Only word-aligned stack accesses.
        return (offA % 4) == 0;
    }

    private boolean isEligibleSpSlot(LswInstr a) {
        if (a.isLabelMode()) {
            return false;
        }
        if (a.getRt() != Register.SP) {
            return false;
        }
        int off = a.getImmediate();
        if (off == 0) {
            return false;
        }
        return (off % 4) == 0;
    }

    private static class SlotState {
        Register knownReg = null;
        Register lastStoredReg = null;
        int lastStoreOutIndex = -1;
        boolean readSinceLastStore = true;
    }

    private void clearSlotStates(Map<Integer, SlotState> slotStates) {
        slotStates.clear();
    }

    private void invalidateByDefinedReg(Map<Integer, SlotState> slotStates, Register defined) {
        for (SlotState st : slotStates.values()) {
            if (st.knownReg == defined) {
                st.knownReg = null;
            }
            if (st.lastStoredReg == defined) {
                st.lastStoredReg = null;
            }
        }
    }

    private Register getDefinedRegister(MipsInstr instr) {
        if (instr instanceof LswInstr) {
            LswInstr m = (LswInstr) instr;
            if (m.getOp().equals("lw")) {
                return m.getRs();
            }
            return null;
        }
        if (instr instanceof MoveInstr) {
            return ((MoveInstr) instr).getTo();
        }
        if (instr instanceof LiInstr) {
            return ((LiInstr) instr).getTo();
        }
        if (instr instanceof LaInstr) {
            return ((LaInstr) instr).getTo();
        }
        if (instr instanceof IInstr) {
            IInstr i = (IInstr) instr;
            // Most I-type ops define the first printed register.
            if (i.getRt() == null) {
                return null;
            }
            return i.getRs();
        }
        if (instr instanceof RInstr) {
            Register ans = ((RInstr) instr).getAns();
            if (ans == Register.HI || ans == Register.LO) {
                return null;
            }
            return ans;
        }
        if (instr instanceof SpecialInstr) {
            return ((SpecialInstr) instr).getReg();
        }
        return null;
    }
}

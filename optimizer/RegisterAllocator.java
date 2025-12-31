package optimizer;

import llvm.BasicBlock;
import llvm.Function;
import llvm.IrModule;
import llvm.Value;
import llvm.instr.AllocaInstr;
import llvm.instr.CallInstr;
import llvm.instr.Instruction;
import llvm.instr.MoveInstr;
import llvm.instr.PhiInstr;
import llvm.instr.ZextInstr;
import mips.Register;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RegisterAllocator {
    private static final List<Register> REG_POOL = Register.allocatablePool();
    private static final List<Register> CALLER_POOL = Register.callerSavedPool();
    private static final List<Register> CALLEE_POOL = Register.calleeSavedPool();

    public static void allocate(IrModule module) {
        for (Function function : module.getFunctions()) {
            allocateFunction(function);
        }
    }

    private static void allocateFunction(Function function) {
        if (function == null || function.getBasicBlocks().isEmpty()) {
            return;
        }

        // New strategy: linear-scan allocation with a simple spill heuristic.
        // The original graph-coloring implementation is kept below (commented out).
        allocateFunctionLinearScan(function);
        return;
    }

    /**
     * Linear-scan register allocation.
     *
     * Policy:
     * - Build live intervals [start,end] for allocatable SSA values (Instruction results).
     * - Scan in order of interval start.
     * - Allocate a free physical register if possible.
     * - If none are free, select an active value to spill based on smallest user count
     *   (Instruction.getUserList().size()). If the victim has 0 users, we simply reuse its reg.
     * - Values that cross a call are conservatively spilled.
     */
    private static void allocateFunctionLinearScan(Function function) {
        // Recompute liveness with SSA phi semantics.
        // NOTE: BasicBlock.initUseDef/updateLive treat all instruction operands as "uses",
        // which is incorrect for:
        // - PhiInstr: operands are edge-uses in predecessors, not uses in the phi block.
        // - MoveInstr: operand1 is a destination (def), not a use.
        // With Mem2Reg, phi density is high; getting this right is a major cycle reducer.

        // Precompute edge phi uses: for edge (pred -> succ), collect phi incoming values used on that edge.
        HashMap<BasicBlock, HashMap<BasicBlock, HashSet<Instruction>>> edgePhiUses = new HashMap<>();
        for (BasicBlock succ : function.getBasicBlocks()) {
            for (Instruction ins : succ.getInstructions()) {
                if (!(ins instanceof PhiInstr)) {
                    break;
                }
                PhiInstr phi = (PhiInstr) ins;
                for (int i = 0; i < phi.defBlock.size(); i++) {
                    BasicBlock pred = phi.defBlock.get(i);
                    Value v = phi.getUseValue(i);
                    if (pred == null) {
                        continue;
                    }
                    if (v instanceof Instruction) {
                        Instruction vin = (Instruction) v;
                        if (isAllocatable(vin)) {
                            edgePhiUses
                                .computeIfAbsent(pred, k -> new HashMap<>())
                                .computeIfAbsent(succ, k -> new HashSet<>())
                                .add(vin);
                        }
                    }
                }
            }
        }

        // Compute block-local use/def sets under phi/move semantics.
        for (BasicBlock block : function.getBasicBlocks()) {
            block.useReg.clear();
            block.defReg.clear();
            block.liveIn.clear();
            block.liveOut.clear();

            for (Instruction ins : block.getInstructions()) {
                if (ins instanceof PhiInstr) {
                    if (isAllocatable(ins)) {
                        block.defReg.add(ins);
                    }
                    continue;
                }

                if (ins instanceof MoveInstr) {
                    Value src = ins.getUseValue(0);
                    Value dst = ins.getUseValue(1);
                    if (src instanceof Instruction) {
                        Instruction s = (Instruction) src;
                        if (isAllocatable(s) && !block.defReg.contains(s)) {
                            block.useReg.add(s);
                        }
                    }
                    if (dst instanceof Instruction) {
                        Instruction d = (Instruction) dst;
                        if (isAllocatable(d)) {
                            block.defReg.add(d);
                        }
                    }
                    continue;
                }

                for (Value op : ins.getOperands()) {
                    if (op instanceof Instruction) {
                        Instruction o = (Instruction) op;
                        if (isAllocatable(o) && !block.defReg.contains(o)) {
                            block.useReg.add(o);
                        }
                    }
                }
                if (isAllocatable(ins)) {
                    block.defReg.add(ins);
                }
            }
        }

        // Iterative dataflow:
        // liveOut[B] = U_{S in succ(B)} (liveIn[S] U phiUseOnEdge(B,S))
        // liveIn[B]  = use[B] U (liveOut[B] - def[B])
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock block : function.getBasicBlocks()) {
                HashSet<Instruction> newOut = new HashSet<>();
                for (BasicBlock succ : block.next) {
                    newOut.addAll(succ.liveIn);
                    HashSet<Instruction> phiUse = edgePhiUses
                        .getOrDefault(block, new HashMap<>())
                        .getOrDefault(succ, new HashSet<>());
                    newOut.addAll(phiUse);
                }

                HashSet<Instruction> newIn = new HashSet<>(block.useReg);
                for (Instruction v : newOut) {
                    if (!block.defReg.contains(v)) {
                        newIn.add(v);
                    }
                }

                if (!newOut.equals(block.liveOut)) {
                    block.liveOut = newOut;
                    changed = true;
                }
                if (!newIn.equals(block.liveIn)) {
                    block.liveIn = newIn;
                    changed = true;
                }
            }
        }

        // Linear order of instructions.
        // We use a split position space to model "use before def" within one instruction:
        //   usePos(i) = 2*i
        //   defPos(i) = 2*i + 1
        // This prevents artificial overlap for SSA temp chains (t1 used by inst i, t2 defined by inst i).
        HashMap<Instruction, Integer> position = new HashMap<>();
        ArrayList<Instruction> linear = new ArrayList<>();
        int idx = 0;
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction instr : block.getInstructions()) {
                position.put(instr, idx++);
                linear.add(instr);
            }
        }

        java.util.function.IntUnaryOperator usePos = (i) -> 2 * i;
        java.util.function.IntUnaryOperator defPos = (i) -> 2 * i + 1;

        // Live ranges and cross-call marking.
        HashMap<Instruction, int[]> liveRange = new HashMap<>();
        HashMap<Instruction, Boolean> crossCall = new HashMap<>();

        for (BasicBlock block : function.getBasicBlocks()) {
            ArrayList<Instruction> instrs = block.getInstructions();
            if (instrs.isEmpty()) {
                continue;
            }

            // Ensure values live-out of this block extend their intervals to the end of the block.
            int lastIndex = position.get(instrs.get(instrs.size() - 1));
            int blockEndPos = defPos.applyAsInt(lastIndex) + 1;

            HashSet<Instruction> live = new HashSet<>();
            for (Instruction l : block.liveOut) {
                if (isAllocatable(l)) {
                    live.add(l);
                    touchRange(liveRange, l, blockEndPos);
                }
            }

            for (int i = instrs.size() - 1; i >= 0; i--) {
                Instruction instr = instrs.get(i);
                int insnIndex = position.get(instr);
                int curUsePos = usePos.applyAsInt(insnIndex);
                int curDefPos = defPos.applyAsInt(insnIndex);

                if (instr instanceof CallInstr) {
                    // Only real `jal` calls clobber our allocated regs.
                    // Builtins (getint/putint/putstr/putch) are lowered to syscalls and don't
                    // touch $s/$t regs in our model.
                    Value callee = instr.getUseValue(0);
                    String name = callee == null ? "" : callee.getName();
                    boolean isBuiltin = name.equals("putint") || name.equals("putstr") || name.equals("getint") || name.equals("putch");
                    if (!isBuiltin) {
                        // Anything live across a call must be kept either in callee-saved regs or spilled.
                        for (Instruction v : live) {
                            crossCall.put(v, true);
                        }
                    }
                }

                if (instr instanceof PhiInstr) {
                    // Phi operands are edge-uses; handled via edgePhiUses in predecessor liveOut.
                    if (isAllocatable(instr)) {
                        live.remove(instr);
                        touchRange(liveRange, instr, curDefPos);
                    }
                    continue;
                }

                if (instr instanceof MoveInstr) {
                    // Move semantics: use(src), def(dst).
                    Value src = instr.getUseValue(0);
                    Value dst = instr.getUseValue(1);
                    if (dst instanceof Instruction) {
                        Instruction d = (Instruction) dst;
                        if (isAllocatable(d)) {
                            live.remove(d);
                            touchRange(liveRange, d, curDefPos);
                        }
                    }
                    if (src instanceof Instruction) {
                        Instruction s = (Instruction) src;
                        if (isAllocatable(s)) {
                            live.add(s);
                            touchRange(liveRange, s, curUsePos);
                        }
                    }
                    continue;
                }

                if (isAllocatable(instr)) {
                    live.remove(instr);
                    touchRange(liveRange, instr, curDefPos);
                }

                for (Value op : instr.getOperands()) {
                    if (op instanceof Instruction) {
                        Instruction def = (Instruction) op;
                        if (!isAllocatable(def)) {
                            continue;
                        }
                        live.add(def);
                        touchRange(liveRange, def, curUsePos);
                    }
                }
            }
        }

        // Build intervals list.
        class Interval {
            final Instruction instr;
            final int start;
            final int end;
            Interval(Instruction instr, int start, int end) {
                this.instr = instr;
                this.start = start;
                this.end = end;
            }
        }
        ArrayList<Interval> intervals = new ArrayList<>();
        for (Instruction instr : linear) {
            if (!isAllocatable(instr)) {
                instr.spill();
                continue;
            }
            int[] r = liveRange.get(instr);
            if (r == null) {
                // No observed uses/defs; treat as dead.
                instr.spill();
                continue;
            }
            intervals.add(new Interval(instr, r[0], r[1]));
        }
        intervals.sort((a, b) -> {
            if (a.start != b.start) return Integer.compare(a.start, b.start);
            return Integer.compare(a.end, b.end);
        });

        // Active set and free registers split into caller-saved and callee-saved.
        ArrayList<Interval> active = new ArrayList<>();
        ArrayDeque<Register> freeCaller = new ArrayDeque<>(CALLER_POOL);
        ArrayDeque<Register> freeCallee = new ArrayDeque<>(CALLEE_POOL);
        HashMap<Instruction, Register> assigned = new HashMap<>();

        // Helper: expire intervals with end < current start.
        java.util.function.IntConsumer expire = (curStart) -> {
            active.sort((x, y) -> Integer.compare(x.end, y.end));
            ArrayList<Interval> still = new ArrayList<>();
            for (Interval it : active) {
                if (it.end < curStart) {
                    Register r = assigned.get(it.instr);
                    if (r != null) {
                        // Prefer reusing a register freed "just now".
                        if (CALLEE_POOL.contains(r)) {
                            freeCallee.addFirst(r);
                        } else {
                            freeCaller.addFirst(r);
                        }
                    }
                } else {
                    still.add(it);
                }
            }
            active.clear();
            active.addAll(still);
        };

        for (Interval cur : intervals) {
            expire.accept(cur.start);

            boolean needsCallee = crossCall.getOrDefault(cur.instr, false);
            if (needsCallee) {
                if (!freeCallee.isEmpty()) {
                    Register r = freeCallee.removeFirst();
                    assigned.put(cur.instr, r);
                    cur.instr.assignRegister(r);
                    active.add(cur);
                    continue;
                }
            } else {
                if (!freeCaller.isEmpty()) {
                    Register r = freeCaller.removeFirst();
                    assigned.put(cur.instr, r);
                    cur.instr.assignRegister(r);
                    active.add(cur);
                    continue;
                }
                if (!freeCallee.isEmpty()) {
                    // Fall back to callee-saved if caller pool exhausted.
                    Register r = freeCallee.removeFirst();
                    assigned.put(cur.instr, r);
                    cur.instr.assignRegister(r);
                    active.add(cur);
                    continue;
                }
            }

            // No free registers: decide whether to spill current or spill one of the active intervals.
            // Cost model (aligned with scoring): spilling an SSA value causes ~1 store (def) + N loads (uses).
            // Each memory op is weight 3, so cost ~= 3 * (1 + uses).
            java.util.function.ToIntFunction<Instruction> spillCost = (ins) -> {
                int uses = ins.getUserList().size();
                return 3 * (1 + Math.max(0, uses));
            };

            int curCost = spillCost.applyAsInt(cur.instr);

            Interval victim = null;
            int victimCost = Integer.MAX_VALUE;
            int victimEnd = Integer.MIN_VALUE;
            for (Interval it : active) {
                Register r = assigned.get(it.instr);
                if (r == null) {
                    continue;
                }

                // If current must be callee-saved, only consider victims holding a callee reg.
                if (needsCallee && !CALLEE_POOL.contains(r)) {
                    continue;
                }

                int cost = spillCost.applyAsInt(it.instr);
                if (cost < victimCost) {
                    victimCost = cost;
                    victimEnd = it.end;
                    victim = it;
                } else if (cost == victimCost) {
                    // Tie-break: prefer spilling the one that stays live longer.
                    if (it.end > victimEnd) {
                        victimEnd = it.end;
                        victim = it;
                    }
                }
            }

            if (victim == null) {
                cur.instr.spill();
                continue;
            }

            // If current is cheaper (or equal) to spill, spill current and keep active regs unchanged.
            if (curCost <= victimCost) {
                cur.instr.spill();
                continue;
            }

            Register r = assigned.get(victim.instr);
            if (r == null) {
                cur.instr.spill();
                continue;
            }

            // If current needs callee-saved but victim reg isn't callee-saved, don't reuse.
            if (needsCallee && !CALLEE_POOL.contains(r)) {
                cur.instr.spill();
                continue;
            }

            // Spill victim and reuse its register for current.
            victim.instr.spill();
            assigned.remove(victim.instr);
            active.remove(victim);

            assigned.put(cur.instr, r);
            cur.instr.assignRegister(r);
            active.add(cur);
        }

        // Propagate register aliases created by zext instructions.
        for (Interval it : intervals) {
            Instruction instr = it.instr;
            if (!(instr instanceof ZextInstr)) {
                continue;
            }
            Value from = instr.getUseValue(0);
            if (from instanceof Instruction) {
                Instruction base = (Instruction) from;
                if (!base.isSpilled() && base.getAssignedRegister() != null) {
                    instr.assignRegister(base.getAssignedRegister());
                }
            }
        }
    }

    private static boolean isAllocatable(Instruction instr) {
        if (instr instanceof AllocaInstr) {
            return false;
        }
        if (instr.getType() == null) {
            return false;
        }
        // Pointer SSA values are generally safe to keep in registers on MIPS.
        // However, pointer-typed phi values are special: if a ptr-phi survives into lowering
        // without being fully materialized by move insertion on all predecessor edges,
        // allocating it to a register can leave that register undefined on some paths.
        // Keep ptr-phis spilled conservatively to avoid OCE (e.g., sw ... 0($sX) with $sX uninitialized).
        if (instr instanceof PhiInstr) {
            if (instr.getType().equals("ptr")) {
                return false;
            }
            String ts = instr.getType().toString();
            if (ts != null && ts.endsWith("*")) {
                return false;
            }
        }

        if (instr.getType().equals("ptr")) {
            return true;
        }
        String typeName = instr.getType().toString();
        return "i32".equals(typeName) || "i1".equals(typeName) || (typeName != null && typeName.endsWith("*"));
    }

    // --- Original graph coloring implementation helpers (unused) ---
    // Kept intentionally for reference / future comparison.
    //
    // private static void addNode(HashMap<Instruction, HashSet<Instruction>> graph, Instruction instr) {
    //     if (!isAllocatable(instr)) {
    //         instr.spill();
    //         return;
    //     }
    //     graph.computeIfAbsent(instr, k -> new HashSet<>());
    // }
    //
    // private static void addEdge(HashMap<Instruction, HashSet<Instruction>> graph, Instruction a, Instruction b) {
    //     if (a == b) {
    //         return;
    //     }
    //     if (!isAllocatable(a) || !isAllocatable(b)) {
    //         return;
    //     }
    //     graph.computeIfAbsent(a, k -> new HashSet<>()).add(b);
    //     graph.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    // }

    private static void touchRange(HashMap<Instruction, int[]> liveRange, Instruction instr, int pos) {
        if (!isAllocatable(instr)) {
            return;
        }
        int[] range = liveRange.get(instr);
        if (range == null) {
            range = new int[] {pos, pos};
            liveRange.put(instr, range);
        } else {
            range[0] = Math.min(range[0], pos);
            range[1] = Math.max(range[1], pos);
        }
    }

    // Graph-coloring spill heuristics (unused)
    //
    // private static Instruction pickLowDegree(Set<Instruction> nodes, Map<Instruction, Integer> degree, int K) {
    //     Instruction best = null;
    //     int bestDeg = Integer.MAX_VALUE;
    //     for (Instruction n : nodes) {
    //         int deg = degree.getOrDefault(n, 0);
    //         if (deg < K && deg < bestDeg) {
    //             bestDeg = deg;
    //             best = n;
    //         }
    //     }
    //     return best;
    // }
    //
    // private static Instruction pickSpill(Set<Instruction> nodes, Map<Instruction, Integer> degree,
    //                                       Map<Instruction, int[]> range, Map<Instruction, Boolean> crossCall) {
    //     Instruction cand = null;
    //     double bestScore = Double.MAX_VALUE;
    //     for (Instruction n : nodes) {
    //         int deg = Math.max(1, degree.getOrDefault(n, 0));
    //         int[] r = range.get(n);
    //         int len = (r == null) ? 1 : (r[1] - r[0] + 1);
    //         double score = (double) len / deg;
    //         if (crossCall.getOrDefault(n, false)) {
    //             score *= 0.5; // prefer spilling values that cross calls
    //         }
    //         if (score < bestScore) {
    //             bestScore = score;
    //             cand = n;
    //         }
    //     }
    //     return cand;
    // }
}

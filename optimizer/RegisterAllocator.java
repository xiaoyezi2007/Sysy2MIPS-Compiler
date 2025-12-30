package optimizer;

import llvm.BasicBlock;
import llvm.Function;
import llvm.IrModule;
import llvm.Value;
import llvm.instr.AllocaInstr;
import llvm.instr.CallInstr;
import llvm.instr.Instruction;
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
    private static final List<Register> REG_POOL = Arrays.asList(
        Register.S0, Register.S1, Register.S2, Register.S3,
        Register.S4, Register.S5, Register.S6, Register.S7,
        Register.T0, Register.T1, Register.T2, Register.T3,
        Register.T4, Register.T5, Register.T6, Register.T7
    );

    public static void allocate(IrModule module) {
        for (Function function : module.getFunctions()) {
            allocateFunction(function);
        }
    }

    private static void allocateFunction(Function function) {
        if (function == null || function.getBasicBlocks().isEmpty()) {
            return;
        }

        // Recompute liveness at block granularity.
        for (BasicBlock block : function.getBasicBlocks()) {
            block.useReg.clear();
            block.defReg.clear();
            block.liveIn.clear();
            block.liveOut.clear();
            block.initUseDef();
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock block : function.getBasicBlocks()) {
                if (block.updateLive()) {
                    changed = true;
                }
            }
        }

        // Linear order of instructions for spill heuristics.
        HashMap<Instruction, Integer> position = new HashMap<>();
        int idx = 0;
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction instr : block.getInstructions()) {
                position.put(instr, idx++);
            }
        }

        // Interference graph structures.
        HashMap<Instruction, HashSet<Instruction>> graph = new HashMap<>();
        HashMap<Instruction, Boolean> crossCall = new HashMap<>();
        HashMap<Instruction, int[]> liveRange = new HashMap<>(); // [start,end]

        for (BasicBlock block : function.getBasicBlocks()) {
            ArrayList<Instruction> instrs = block.getInstructions();
            if (instrs.isEmpty()) {
                continue;
            }

            // Phi results are live at block entry; they interfere with liveOut.
            HashSet<Instruction> live = new HashSet<>();
            for (Instruction l : block.liveOut) {
                if (isAllocatable(l)) {
                    live.add(l);
                }
            }
            for (Instruction instr : instrs) {
                if (instr instanceof PhiInstr && isAllocatable(instr)) {
                    addNode(graph, instr);
                    for (Instruction l : live) {
                        addEdge(graph, instr, l);
                    }
                }
            }

            // Backward scan for non-phi instructions.
            for (int i = instrs.size() - 1; i >= 0; i--) {
                Instruction instr = instrs.get(i);
                if (instr instanceof PhiInstr) {
                    continue;
                }
                int curPos = position.get(instr);
                addNode(graph, instr);

                if (instr instanceof CallInstr) {
                    for (Instruction v : live) {
                        addNode(graph, v);
                        crossCall.put(v, true);
                    }
                }

                if (isAllocatable(instr)) {
                    for (Instruction l : live) {
                        addEdge(graph, instr, l);
                    }
                    live.remove(instr);
                    touchRange(liveRange, instr, curPos);
                }

                for (Value op : instr.getOperands()) {
                    if (op instanceof Instruction) {
                        Instruction def = (Instruction) op;
                        if (!isAllocatable(def)) {
                            continue;
                        }
                        live.add(def);
                        touchRange(liveRange, def, curPos);
                    }
                }
            }

            // Phi operands live on incoming edges from predecessors.
            for (Instruction instr : instrs) {
                if (!(instr instanceof PhiInstr)) {
                    continue;
                }
                PhiInstr phi = (PhiInstr) instr;
                for (int k = 0; k < phi.defBlock.size(); k++) {
                    Value op = phi.getUseValue(k);
                    if (!(op instanceof Instruction)) {
                        continue;
                    }
                    Instruction val = (Instruction) op;
                    if (!isAllocatable(val)) {
                        continue;
                    }
                    BasicBlock pred = phi.defBlock.get(k);
                    // Live set at end of pred flows into the phi edge plus the operand itself.
                    HashSet<Instruction> predLive = new HashSet<>();
                    for (Instruction l : pred.liveOut) {
                        if (isAllocatable(l)) {
                            predLive.add(l);
                        }
                    }
                    predLive.add(val);
                    for (Instruction l : predLive) {
                        addEdge(graph, val, l);
                    }
                    addEdge(graph, val, phi);
                    touchRange(liveRange, val, position.get(pred.getInstructions().get(pred.getInstructions().size() - 1)));
                }
            }
        }

        // Simplify (graph coloring with possible spills).
        int K = REG_POOL.size();
        HashMap<Instruction, Integer> degree = new HashMap<>();
        for (Map.Entry<Instruction, HashSet<Instruction>> e : graph.entrySet()) {
            degree.put(e.getKey(), e.getValue().size());
        }

        ArrayDeque<Instruction> stack = new ArrayDeque<>();
        HashSet<Instruction> spilled = new HashSet<>();
        HashSet<Instruction> nodes = new HashSet<>(graph.keySet());

        while (!nodes.isEmpty()) {
            Instruction pick = pickLowDegree(nodes, degree, K);
            if (pick == null) {
                pick = pickSpill(nodes, degree, liveRange, crossCall);
                spilled.add(pick);
            }
            stack.push(pick);
            nodes.remove(pick);
            for (Instruction n : graph.getOrDefault(pick, new HashSet<>())) {
                if (nodes.contains(n)) {
                    degree.put(n, degree.get(n) - 1);
                }
            }
        }

        HashMap<Instruction, Register> coloring = new HashMap<>();
        while (!stack.isEmpty()) {
            Instruction v = stack.pop();
            if (spilled.contains(v) || crossCall.getOrDefault(v, false)) {
                continue;
            }
            HashSet<Register> used = new HashSet<>();
            for (Instruction n : graph.getOrDefault(v, new HashSet<>())) {
                Register r = coloring.get(n);
                if (r != null) {
                    used.add(r);
                }
            }
            Register chosen = null;
            for (Register r : REG_POOL) {
                if (!used.contains(r)) {
                    chosen = r;
                    break;
                }
            }
            if (chosen == null) {
                spilled.add(v);
            } else {
                coloring.put(v, chosen);
            }
        }

        for (Instruction instr : graph.keySet()) {
            Register r = coloring.get(instr);
            if (r != null && !crossCall.getOrDefault(instr, false)) {
                instr.assignRegister(r);
            } else {
                instr.spill();
            }
        }

        // Propagate register aliases created by zext instructions.
        for (Instruction instr : graph.keySet()) {
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
        if (instr instanceof AllocaInstr || instr instanceof PhiInstr) {
            return false;
        }
        String typeName = instr.getType().toString();
        return "i32".equals(typeName) || "i1".equals(typeName);
    }

    private static void addNode(HashMap<Instruction, HashSet<Instruction>> graph, Instruction instr) {
        if (!isAllocatable(instr)) {
            instr.spill();
            return;
        }
        graph.computeIfAbsent(instr, k -> new HashSet<>());
    }

    private static void addEdge(HashMap<Instruction, HashSet<Instruction>> graph, Instruction a, Instruction b) {
        if (a == b) {
            return;
        }
        if (!isAllocatable(a) || !isAllocatable(b)) {
            return;
        }
        graph.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        graph.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

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

    private static Instruction pickLowDegree(Set<Instruction> nodes, Map<Instruction, Integer> degree, int K) {
        Instruction best = null;
        int bestDeg = Integer.MAX_VALUE;
        for (Instruction n : nodes) {
            int deg = degree.getOrDefault(n, 0);
            if (deg < K && deg < bestDeg) {
                bestDeg = deg;
                best = n;
            }
        }
        return best;
    }

    private static Instruction pickSpill(Set<Instruction> nodes, Map<Instruction, Integer> degree,
                                          Map<Instruction, int[]> range, Map<Instruction, Boolean> crossCall) {
        Instruction cand = null;
        double bestScore = Double.MAX_VALUE;
        for (Instruction n : nodes) {
            int deg = Math.max(1, degree.getOrDefault(n, 0));
            int[] r = range.get(n);
            int len = (r == null) ? 1 : (r[1] - r[0] + 1);
            double score = (double) len / deg;
            if (crossCall.getOrDefault(n, false)) {
                score *= 0.5; // prefer spilling values that cross calls
            }
            if (score < bestScore) {
                bestScore = score;
                cand = n;
            }
        }
        return cand;
    }
}

package solver;

import java.util.*;
import  java.io.File;

import plotter.Plot;
import containers.Solution;
import containers.Term;
import scpsolver.lpsolver.LinearProgramSolver;
import scpsolver.lpsolver.SolverFactory;
import scpsolver.problems.LinearProgram;
import  scpsolver.constraints.*;


public class MCKP_Solver {
    int p;
    int N;
    String filename;
    LinkedList<Term>[] data;
    //ArrayList<Term>[] data;
    //ArrayList<Term>[] LP_filtered_data; //instances after removing LP-dominated terms
    LinkedList<Term>[] LP_filtered_data;

    public MCKP_Solver(int _p, int _N, LinkedList<Term>[] _data) throws Exception {
        data = _data;
        this.p = _p;
        this.N = _N;
        LP_filtered_data = new LinkedList[N];


    }

    int total_num_instances(boolean after_lp_filter) { //returns total number of instances
        int c = 0;
        LinkedList<Term>[] data_to_use = data;
        if (after_lp_filter) data_to_use = LP_filtered_data;
        for (LinkedList dd : data_to_use) {
            c += dd.size();
        }
        return c;
    }

    public int upper_bnd_Rate() { // returns an upper bound for the maximum rate achievable, by taking the biggest one in every channel
        int res = 0;
        for (int i = 0; i < N; i++) {
            int maxr = 0;
            for (Term t : data[i]) {
                maxr = Math.max(maxr, t.r);
            }

            res += maxr;
        }
        return res;
    }


    public void remove_impossible_terms() throws Exception { //First step of pre-processing
        Term[] mins = new Term[N]; //Array to hold the pairs with minimum power for each channel
        int power_sum_min = 0;
        for (int i = 0; i < N; i++) {
            Term min = Collections.min(data[i], new power_comparator());
            mins[i] = min;
            power_sum_min += min.p;
        }
        if (power_sum_min > this.p) {
            System.out.println(power_sum_min);
            System.out.println(power_sum_min);

            throw new Exception("Impossible problem instance"); //if minimum power in each channel exceeds budget, then problem is impossible
        }
        for (int n = 0; n < N; n++) {
            LinkedList<Term> channel_n = data[n];
            //HashSet<Term> channel_n_hash = new HashSet<Term>(channel_n);
            final int offset = power_sum_min - mins[n].p;
            channel_n.removeIf(t -> offset + t.p  > this.p);
        }

    }

    public void remove_IP_dominated() {
        for (int n = 0; n < N; n++) {
            LinkedList<Term> channel_n = data[n];
            Collections.sort(channel_n, new power_comparator());
            int max_r = channel_n.get(0).r;
            Iterator<Term> it = channel_n.iterator();
            if(!it.hasNext()) continue;
            else it.next();
            for (; it.hasNext(); ) {
                Term curr = it.next();
                if (curr.r <= max_r) it.remove();
                max_r = Math.max(max_r, curr.r);
            }
        }

    }

    boolean is_point_right(Term p1, Term p2, Term p3) { //true if p1 is on the right of the line p3 -> p2

        return (p2.r - p3.r) * (p1.p - p2.p) >= (p1.r - p2.r) * (p2.p - p3.p);


    }

    public void remove_LP_dominated() {
        for (int n = 0; n < N; n++) {
            //  LP_filtered_data[n] = new ArrayList<Term>();
            LinkedList<Term> channel_n = data[n];
            Collections.sort(channel_n, new power_comparator());
            Stack<Term> upper_convex_hull = new Stack<Term>();
            upper_convex_hull.push(channel_n.get(0));
            for (int i = 1; i < channel_n.size(); i++) {
                Term p1 = channel_n.get(i);
                if (upper_convex_hull.size() > 1) {
                    Term p2 = upper_convex_hull.pop();
                    Term p3 = upper_convex_hull.peek();
                    while (!is_point_right(p1, p2, p3) && upper_convex_hull.size() > 1) {
                        p2 = upper_convex_hull.pop();
                        p3 = upper_convex_hull.peek();


                    }
                    if (is_point_right(p1, p2, p3)) upper_convex_hull.push(p2);
                }
                upper_convex_hull.push(p1);

            }
            LP_filtered_data[n] = new LinkedList<Term>(upper_convex_hull);
        }

    }

    public void visualize_data(int channel, String additionnal_title, boolean lp_filtred) {
        Plot chart = null;
        if (lp_filtred) {
            chart = new Plot("Instance scatter plot - file :" + filename + additionnal_title, LP_filtered_data, channel);

        } else {
            chart = new Plot("Instance scatter plot - file :" + filename + additionnal_title, data, channel);
        }
        chart.setSize(800, 400);
        chart.setLocationRelativeTo(null);
        chart.setVisible(true);
    }

    LinkedList<Term> Sort_by_incremental_efficiency() { //returns an array of all pairs apart form the first pair of each channel, sorted by incremental efficiency
        LinkedList<Term> pairs_sorted_eff = new LinkedList<Term>();
        for (int n = 0; n < N; n++) {
            LinkedList<Term> channel_n = LP_filtered_data[n]; //OPERATES ON data after removing LP dominated terms
            Collections.sort(channel_n, new power_comparator());
            Term curr_pair = channel_n.get(0);
            curr_pair.inc_eff = Double.MAX_VALUE;
            curr_pair.inc_power = curr_pair.p;
            curr_pair.inc_rate = curr_pair.r;
            pairs_sorted_eff.add(curr_pair);
            for (int i = 1; i < channel_n.size(); i++) {
                curr_pair = channel_n.get(i);
                Term prev_pair = channel_n.get(i - 1);
                curr_pair.inc_eff = Double.valueOf(curr_pair.r - prev_pair.r) / (curr_pair.p - prev_pair.p);
                curr_pair.inc_rate = (curr_pair.r - prev_pair.r);
                curr_pair.inc_power = (curr_pair.p - prev_pair.p);
                pairs_sorted_eff.add(curr_pair);
            }
        }
        Collections.sort(pairs_sorted_eff, new inc_eff_comparator().reversed());
        return pairs_sorted_eff;
    }

    public Solution greedy_LP() {
        LinkedList<Term> sorted_inc = Sort_by_incremental_efficiency();

        int Power_Bud = this.p;
        double Rate = 0;
        ArrayList<Term>[] solutions = new ArrayList[N];
        for (int j = 0; j < N; j++) solutions[j] = new ArrayList<>();
        int i = 0;
        Term curr_pair = null;
        while (i < sorted_inc.size() && sorted_inc.get(i).inc_power <= Power_Bud) {
            curr_pair = sorted_inc.get(i);
            Power_Bud -= curr_pair.inc_power;
            Rate += curr_pair.inc_rate;
            Term sol = new Term(curr_pair.p, curr_pair.r, curr_pair.n, 1);
            solutions[curr_pair.n].clear();
            solutions[curr_pair.n].add(sol);
            i++;
        }

        if (Power_Bud > 0 && i < sorted_inc.size()) {
            curr_pair = sorted_inc.get(i);
            double x = Double.valueOf(Power_Bud) / curr_pair.inc_power;

            Term sol1 = new Term(curr_pair.p, curr_pair.r, curr_pair.n, x);
            Term prev_pair = solutions[sol1.n].get(0);
            Term sol2 = new Term(prev_pair.p, prev_pair.r, prev_pair.n, 1 - x);
            Rate += x * curr_pair.inc_rate;
            Power_Bud -= x * curr_pair.inc_power;
            solutions[sol1.n].clear();
            solutions[sol1.n].add(sol2);
            solutions[sol1.n].add(sol1);

        }
        return new Solution(Rate, solutions);


    }

    public double LP_solver() {
        ArrayList<Double> rates = new ArrayList<Double>();
        ArrayList<Double> powers = new ArrayList<Double>();
        int[] channels_sizes = new int[N];
        for (int i = 0; i < N; i++) {
            for (Term p : data[i]) {
                rates.add(Double.valueOf(p.r));
                powers.add(Double.valueOf(p.p));
            }
            if (i == 0) channels_sizes[i] = 0;
            else channels_sizes[i] = channels_sizes[i - 1] + data[i - 1].size();
        }
        LinearProgram lp = new LinearProgram(rates.stream().mapToDouble(Double::doubleValue).toArray());
        lp.addConstraint(new LinearSmallerThanEqualsConstraint(powers.stream().mapToDouble(Double::doubleValue).toArray(), this.p, "power budget constraint"));
        for (int i = 0; i < N; i++) {
            ArrayList<Double> sparse_vec_ch_i = new ArrayList<Double>(); //to hold the list used to build the ith constraint
            Double[] zeros_arr_before = new Double[channels_sizes[i]];
            Arrays.fill(zeros_arr_before, 0.0);
            Collections.addAll(sparse_vec_ch_i, zeros_arr_before);
            Double[] ones_arr = new Double[data[i].size()];
            Arrays.fill(ones_arr, 1.0);
            Collections.addAll(sparse_vec_ch_i, ones_arr);
            if (i < N - 1) {
                Double[] zeros_arr_after = new Double[channels_sizes[N - 1] + data[N - 1].size() - channels_sizes[i + 1]];
                Arrays.fill(zeros_arr_after, 0.0);
                Collections.addAll(sparse_vec_ch_i, zeros_arr_after);
            }
            lp.addConstraint(new LinearEqualsConstraint(sparse_vec_ch_i.stream().mapToDouble(Double::doubleValue).toArray(), 1.0, "One user per channel constraint" + String.valueOf(i)));

        }
        double[] is_int = new double[rates.size()];
        for (int i = 0; i < rates.size(); i++) {
            is_int[i] = 0;
        }
        lp.setLowerbound(is_int);

        LinearProgramSolver solver = SolverFactory.newDefault();
        double[] sol = solver.solve(lp);


        double maxrate = 0;
        for (int i = 0; i < rates.size(); i++) {
            maxrate += sol[i] * rates.get(i);
        }
        return maxrate;

    }

    public int DP_1() { //First Implementation of Dynamic programming
        /*
        L[p-1] holds maximum rate with power budget p
          */
        int[] L = new int[p];
        for (int i = 1; i <= p; i++) {
            int maxr = 0;
            for (Term curr_pair : data[0]) {
                if (curr_pair.p <= i) maxr = Math.max(maxr, curr_pair.r);
            }
            L[i - 1] = maxr;
        }
        for (int i = 1; i < N; i++) {
            int[] tempL = new int[p];
            for (int power = 1; power <= p; power++) {
                LinkedList<Term> curr_channel = data[i];
                int max_r = 0;
                for (Term pp : curr_channel) {
                    if (pp.p < power && L[power - pp.p - 1] > 0) max_r = Math.max(max_r, L[power - pp.p - 1] + pp.r);
                }
                tempL[power - 1] = max_r;
            }
            L = tempL;
        }
        return L[p - 1];
    }

    public int DP_2(int U) { //First Implementation of Dynamic programming given upper bound U of rates
        int[] L = new int[U];
        for (int i = 1; i <= U; i++) {
            int minp = 0;
            for (Term curr_pair : data[0]) {
                if (curr_pair.r == i) {
                    if (minp == 0) minp = curr_pair.p;
                    else minp = Math.min(minp, curr_pair.p);
                }
            }
            L[i - 1] = minp;
        }
        for (int n = 1; n < N; n++) {
            int[] tempL = new int[U];
            for (int rate = 1; rate <= U; rate++) {
                LinkedList<Term> curr_channel = data[n];
                int min_p = 0;
                for (Term pp : curr_channel) {
                    if (pp.r < rate && L[rate - pp.r - 1] > 0) {
                        if (min_p == 0) min_p = L[rate - pp.r - 1] + pp.p;
                        else min_p = Math.min(min_p, L[rate - pp.r - 1] + pp.p);
                    }
                }
                tempL[rate - 1] = min_p;
            }
            L = tempL;
        }
        for (int r = U; r >= 1; r--) {
            if (L[r - 1] <= p && L[r - 1] > 0) return r;
        }
        return -1;
    }

    Bounds Greedy_bound(int curr_channel, int Power_Bud, LinkedList<Term> sorted_inc) {
        double Rate = 0;
        int i = 0;
        Term curr_pair = null;
        while (i < sorted_inc.size() && sorted_inc.get(i).inc_power <= Power_Bud) {
            curr_pair = sorted_inc.get(i);
            if (curr_pair.n >= curr_channel) {
                Power_Bud -= curr_pair.inc_power;
                Rate += curr_pair.inc_rate;
                Term sol = new Term(curr_pair.p, curr_pair.r, curr_pair.n, 1);
            }
            i++;
        }
        int LB = (int) Rate;
        if (Power_Bud > 0 && i < sorted_inc.size()) {
            curr_pair = sorted_inc.get(i);
            double x = Double.valueOf(Power_Bud) / curr_pair.inc_power;
            Rate += x * curr_pair.inc_rate;
            Power_Bud -= x * curr_pair.inc_power;

        }
        return new Bounds(Rate, LB);
    }

    public int Braunch_and_bound() {
        LinkedList<Term> so = Sort_by_incremental_efficiency();
        Bounds curbound = Greedy_bound(0, this.p, so);
        BB(0, 0, 0, curbound, so);
        return curbound.LB;
    }

    public void BB(int curr_channel, int Power_used, int rate_achieved, Bounds curr_bounds, LinkedList<Term> sorted_inc) {
        if (Power_used >= this.p) return;
        Bounds braunch_bound;
        /*
        System.out.print(braunch_bound.UB + rate_achieved);
        System.out.print(",");
        System.out.print(braunch_bound.LB + rate_achieved);
        System.out.print(",");
        System.out.println(curr_channel);
*/

        for (Term pai : data[curr_channel]) {
            if (pai.p + Power_used > this.p) continue;
            if (curr_channel < N - 1) {
                braunch_bound = Greedy_bound(curr_channel + 1, this.p - Power_used - pai.p, sorted_inc);
                if (braunch_bound.UB + rate_achieved + pai.r > curr_bounds.LB) {
                    curr_bounds.LB = Math.max(curr_bounds.LB, braunch_bound.LB + rate_achieved + pai.r);
                    BB(curr_channel + 1, Power_used + pai.p, rate_achieved + pai.r, curr_bounds, sorted_inc);
                }
            } else curr_bounds.LB = Math.max(curr_bounds.LB, rate_achieved + pai.r);
        }
    }

    public int BB_with_queue() {
        LinkedList<Term> sorted_inc = Sort_by_incremental_efficiency();
        Queue<par> dd = new LinkedList<>();
        dd.add(new par(0, 0, 0));
        Bounds curr_bounds = Greedy_bound(0, 0, sorted_inc);
        while (dd.size() > 0) {

            par c = dd.remove();
            for (Term pai : data[c.curr_channel]) {
                if (pai.p + c.power_used > this.p) continue;
                if (c.curr_channel < N - 1) {
                    Bounds braunch_bound = Greedy_bound(c.curr_channel + 1, this.p - c.power_used - pai.p, sorted_inc);
                    if (braunch_bound.UB + c.rate_achieved + pai.r > curr_bounds.LB) {
                        dd.add(new par(c.curr_channel + 1, c.power_used + pai.p, c.rate_achieved + pai.r));
                    }
                    curr_bounds.LB = Math.max(curr_bounds.LB, c.rate_achieved + pai.r + braunch_bound.LB);
                } else curr_bounds.LB = Math.max(curr_bounds.LB, c.rate_achieved + pai.r);
            }

        }
        return curr_bounds.LB;
    }
}

class par {
    int curr_channel;
    int power_used;
    int rate_achieved;
    public par(int c, int p, int r) {
        curr_channel = c;
        power_used = p;
        rate_achieved = r;
    }
} //class used to hold nodes parameters
class power_comparator implements Comparator{
    public int compare(Object o1, Object o2) {
        Term pair1 = (Term) o1;
        Term pair2 = (Term) o2;
        if (pair1.p > pair2.p) return 1;
        else if(pair1.p < pair2.p) return -1;
        else {
            if (pair1.r > pair2.r) return -1;
            else if (pair1.r < pair2.r) return 1;
            else return 0;
        }

    }
}
class inc_eff_comparator implements  Comparator {
    public int compare(Object o1, Object o2) {
        Term pair1 = (Term) o1;
        Term pair2 = (Term) o2;
        return Double.compare(pair1.inc_eff,pair2.inc_eff);
    }
}
class Bounds {
    double UB;
    int LB;
    public Bounds(double ub, int lb) {
        UB = ub;
        LB = lb;
    }
} //used to hold upper and lower bounds for problem




package sim.nodes;

import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import sim.constraints.Interval;
import sim.constraints.Interval.Type;
import Jama.Matrix;

public class MVNModel implements Model {
    // private static Logger logger = Logger.getLogger(Model.class);

    /** number of nodes */
    public int m;

    public static int stat_sentCount = 0;
    public static int stat_tx = 0;

    public Matrix c;
    public Matrix a;
    public Matrix sigma;
    double epsilon;
    public double epsilon1;
    private Matrix sentValues;
    
    /** if each (child) component of head-to-base transmission is known;
     * 1 : Transmitted and value known,
     * -1: transmitted but value unknown,
     * 0 : don't know if transmitted or not */
    int[] known;
    /** total dimension of the distribution; 
     * last m dimensions are always for last epoch
     */
    int dim; 

    Matrix mean, cov;
    int[] sentIndex;
    int[] lastIndex;
    int ts = 0;
    /** time of last transmission from child to head */
    int[] lastFailureTime;
    /** type of transmission from child to head last time */
    Type[] lastTypes;
    
    private SubsetSelector subsetSelector;

    public MVNModel(double epsilon, Matrix c, Matrix a, Matrix sigma) {
        this.c = c;
        this.a = a;
        this.sigma = sigma;
        m = c.getRowDimension();
        this.epsilon = epsilon;
        subsetSelector = new GreedySubsetSelector(this);
    }

    private int[] remainingIndex(int total, int[] state) {
        int count = 0;
        for (int s : state) {
            if (s != 0) {
                count++;
            }
        }
        int[] y = new int[total - count];
        int k = 0;
        for (int i = 0; i < state.length; i++) {
            if (state[i] == 0) {
                y[k++] = i;
            }
        }
        for (int i = state.length; i < total; i++) {
            y[k++] = i;
        }
        return y;
    }

    /**
     * When a new subset is transmitted, they should overwrite previous values in the
     * model. Drop those old elements and marginalize on the latest sent+unknown elements.
     */
    public void marginalize(int[] state) {
        int[] remain = remainingIndex(dim, state);
        mean = mean.getMatrix(remain, 0, 0);
        cov = cov.getMatrix(remain, remain);
        Helper.reset(lastIndex, remain.length - m);
        int j = 0, x = 0;
        for (int i = 0; i < m; i++) {
            if (state[i] != 0) {
                sentIndex[i] = remain.length - m + i;
                x++;
                j++;
            } else {
                sentIndex[i] -= x;
            }
        }
        dim = remain.length;
    }

    private boolean allKnown() {
    	boolean res = true;
        for (int b : known) {
            res = res && (b==1);
        }
        return res;
    }

    /**
     * Takes content of transmission and outputs constraints.
     * 
     * @param type
     *            type of head-to-bs transmission: GOOD, BAD or UNKNOWN
     * @param ntype array of types of child-to-head transmission: GOOD, BAD or UNKNOWN
     * @param val
     *            values vector contained in head-to-bs message
     * @param status
     *            for each component in val, 1 means value known, -1 means value unknown and 0
     *            means not included in the transmitted subset.
     */
    public void makePrediction(Interval.Type type, Interval[] ntype, double[] val, int[] status) {
        if (sentValues == null) {
            ts = 0;
            dim = m;
            sentValues = new Matrix(m, 1); // last sent value for each node

            for (int i = 0; i < m; i++) {
                sentValues.set(i, 0, val[i]);
            }

            // END = 2 * m - 1;
            mean = new Matrix(new double[m], m);
            cov = new Matrix(new double[m][m]);
            // mean initialized to 0 automatically
            // now initialize cov
            cov.setMatrix(0, m - 1, 0, m - 1, sigma);

            sentIndex = new int[m];
            lastIndex = new int[m];
            Helper.reset(sentIndex, 0);
            Helper.reset(lastIndex, 0);

            known = new int[m];
            lastFailureTime = new int[m];
            lastTypes = new Type[m];
            Arrays.fill(known, 1);
            Arrays.fill(lastFailureTime, 0);
            Arrays.fill(lastTypes, Type.GOOD);
            return;
        }

        /* evolve model for each time step*/
        forward();
        marginalize(status);

        /* If head-to-bs is unknown, no constraint can be generated */
        if (type == Type.UNKNOWN) {
            System.out.println("# t " + (ts+1) + " no constraints");
            // don't know if any subset/component is transmitted or not
            Arrays.fill(known, 0);
            return;
        }

        /* copy sent values */
        for (int i = 0; i < m; i++) {
            lastTypes[i] = ntype[i].type;
            if (status[i] == 1) {// newly sent values
                sentValues.set(i, 0, val[i]);
                known[i] = 1;
            } else if (status[i] == -1) {// unknown values
                known[i] = -1;
                // Value is unknown so save ts for use in symbolic computation
                lastFailureTime[i] = ts;
            } else {
                continue;
            }
        }

        /* check if all components are known again */
        boolean allCertain = true;
        for (int b : known)
        	allCertain = allCertain && (b!=0);
        if (!allCertain) {
            System.out.println("# t " + (ts+1) + " no constraints: prediction state not in sync");
            return;        	
        }

        /* which components need be predicted? */
        int[] predictIndex = lastIndex.clone();
        int subsetSize = 0;
        for (int i = 0; i < m; i++) {
            if (status[i] != 0) {
                predictIndex[i] = -1; // no need to predict
                subsetSize++;
            }
        }

        for (int j=0; j<m; j++)
        	if (ntype[j].type!=Type.UNKNOWN && ntype[j].begin!=ts) {
        		System.out.println(String.format("3:%f;%f;%f,%d,%d;%f,%d,%d",-epsilon1,epsilon1,1.0,ts+1,j+1,-1.0,ntype[j].begin+1,j+1));
        }

        /* last transmission for any components is known, so numeric computation */
        if (allKnown()) {
        	// Only happens when current time is in a Good interval
            Matrix prediction = predict(mean, cov, sentIndex, predictIndex, sentValues);
            // now constraints?
            // if (type == Interval.GOOD)

            // produce constraints
            int x = 0; // how many -1s encountered

            for (int j = 0; j < m; j++) {
                if (predictIndex[j] == -1) {
                    x++;
                }
                if (ntype[j].type == Type.GOOD) {
                    if (predictIndex[j] == -1) {
                        if (ntype[j].begin == ts)
                        {
                        	// both tiers transmit, equality constraint
                        	// type 0: x[i,j] = a
                            System.out.println(String.format("0:x[%d,%d] = %f", ts + 1, j + 1, sentValues.get(j, 0)));
                        } else {
                            System.out.println(String.format("1:%d,%d,%f,%f", ts + 1, j + 1, sentValues.get(j, 0) - (epsilon1), sentValues.get(j, 0) + (epsilon1)));
                        }
                    } else {
                        // type 1: a <= x[i,j] <= b
                        if (ntype[j].begin == ts)
                        	System.out.println(String.format("1:%d,%d,%f,%f", ts + 1, j + 1, prediction.get(j - x, 0) - epsilon, prediction.get(j - x, 0) +  epsilon));
                        else
                        	System.out.println(String.format("1:%d,%d,%f,%f", ts + 1, j + 1, prediction.get(j - x, 0) - (epsilon1 + epsilon), prediction.get(j - x, 0) + (epsilon1 + epsilon)));
                    }
                } else if (ntype[j].type == Type.BAD) {
                    if (predictIndex[j] == -1) {
                        // type 2: x[i,j] <a or x[i,j] >b; omit
                    	// Seems we can't derive this, can we?
                        // System.out.println(String.format("2:%d,%d,%f,%f", ts + 1, j + 1, sentValues.get(j, 0) - epsilon1, sentValues.get(j, 0) + epsilon1));
                    } else {
                        // loose bound
                    }
                }
            }
        } else { // symbolic computation
            /* NOTE: Symbolic variable here is NOT real reading, but view of the head.
             * So unless the lower-level intervals are all GOOD, we have no constraints. */
        	boolean lowerAllGood = true;
            for (int i = 0; i < m; i++) /* if a component is unkown, it must appear in symbolic constraint so it's lastType is required to be good */ {
                if (known[i]==-1 && lastTypes[i] != Type.GOOD) {
                	lowerAllGood = false;
                }
            }
            if (!lowerAllGood) {
                return;
            }
            int[] compactPredictIndex = pack(predictIndex); // remove -1 elements

            Matrix predictMean = mean.getMatrix(compactPredictIndex, 0, 0);
            Matrix coef = cov.getMatrix(compactPredictIndex, sentIndex).times(
                    cov.getMatrix(sentIndex, sentIndex).inverse());
            Matrix C = predictMean.minusEquals(coef.times(mean.getMatrix(sentIndex, 0, 0))); // the constant term

            /*
             * constraint will be: C-epsilon <= x- coef*sentValues <= C+epsilon
             * output format: 3:left;right;coef,time,node;coef,time,node;...
             */

            int x = 0; // how many -1's encountered

            for (int j = 0; j < m; j++) {
                if (predictIndex[j] != -1) { // to be predicted
                    if (ntype[j].type == Type.GOOD) {
                        double left = C.get(j - x, 0);
                        double right = C.get(j - x, 0);
                        if (ntype[j].begin == ts) { // begin of a suppression interval
                            left -= epsilon;
                            right += epsilon;
                        }
                        else {
                            left -=  (epsilon + epsilon1);
                            right +=  (epsilon + epsilon1);
                        }
                        double temp = 0, relax = 0;
                        String sVar = "";
                        for (int k = 0; k < m; k++) {
                            if (known[k]==1) {
                                temp += coef.get(j - x, k) * sentValues.get(k, 0);
                            } else {
                                // NOTE: Symbolic variable here is NOT real reading, but view of the head.
                                // So additional relaxation of the bounds should be considered. 
                                sVar += String.format(";%f,%d,%d", -coef.get(j - x, k), lastFailureTime[k] + 1, k + 1);
                                relax += Math.abs(coef.get(j - x, k)) * epsilon1;
                            // relax could be more tight when view of the head *is* actually the real reading
                            }
                        }
                        left += (temp - relax);
                        right += (temp + relax);
                        StringBuffer output = new StringBuffer(String.valueOf("3:" + left + ";" + right));//= String.format("%f <=, arg1)

                        output.append(";1," + (ts + 1) + "," + (1 + j));
                        output.append(sVar);
                        //output.append("<= "+right);
                        System.out.println(output);
                    }
                    else if (ntype[j].type == Type.BAD) {
                    }
                } else {// sent values
                    x++;
                    if (known[j]==1 && ntype[j].type == Type.GOOD) {
                        if (ntype[j].begin == ts)
                        	System.out.println(String.format("0:x[%d,%d] = %f", ts + 1, j + 1, sentValues.get(j, 0)));
                        else
                        	System.out.println(String.format("1:%d,%d,%f,%f", ts + 1, j + 1, sentValues.get(j, 0) - (epsilon1), sentValues.get(j, 0) + (epsilon1)));
                    /*System.out.println(String.format("1:%d,%d,%f,%f", ts+1, j+1, sentValues
                    .get(j, 0)
                    - (epsilon1),  sentValues.get(j, 0)
                    + (epsilon1)));*/
                    } else if (known[j]==1 && ntype[j].type == Type.BAD) {
                        System.out.println(String.format("2:%d,%d,%f,%f", ts + 1, j + 1, sentValues.get(j, 0) - epsilon1, sentValues.get(j, 0) + epsilon1));
                    }
                }
            }

        }
    }

    /**
     * Move forward one epoch. Update mean, cov and indices.
     */
    public void forward() {
        ts++;
        // update distribution params: mean and cov

        Matrix lastMean = mean.getMatrix(lastIndex, 0, 0);
        Matrix currentMean = c.plus(a.times(lastMean));

        // Matrix cov = cov.getMatrix(0, dim-1, 0, dim-1);
        Matrix UL = cov.copy();
        Matrix LL = a.times(cov.getMatrix(lastIndex, 0, dim - 1));
        // oldCov.print(0, 0);
        //Matrix UR = cov.getMatrix(0, dim - 1, lastIndex).times(a.transpose());
        Matrix UR = LL.transpose();
        Matrix temp = new Matrix(m, dim);
        temp.setMatrix(0, m - 1, dim - m, dim - 1, a);
        Matrix LR = sigma.plus(temp.times(cov).times(temp.transpose()));

        // construct new mean and cov
        dim = 2 * m;
        int END = dim - 1;
        Matrix tempMean = new Matrix(dim, 1);
        tempMean.setMatrix(0, m - 1, 0, 0, mean.getMatrix(sentIndex, 0, 0));
        tempMean.setMatrix(m, dim - 1, 0, 0, currentMean);
        mean = tempMean;

        cov = new Matrix(dim, dim);
        cov.setMatrix(0, m - 1, 0, m - 1, UL.getMatrix(sentIndex, sentIndex));
        cov.setMatrix(0, m - 1, m, END, UR.getMatrix(sentIndex, 0, m - 1));
        cov.setMatrix(m, END, 0, m - 1, LL.getMatrix(0, m - 1, sentIndex));
        cov.setMatrix(m, END, m, END, LR);
        // cov is full now
        // lastTimeBegin = m;
        // lastTimeEnd = END;
        Helper.reset(sentIndex, 0);
        Helper.reset(lastIndex, m);
    }

    //@Override
    public int[] send(double[] currentVal) {
        // first time to send: always send out all values
        if (sentValues == null) {
            dim = m;
            sentValues = new Matrix(m, 1); // last sent value for each node

            for (int i = 0; i < m; i++) {
                sentValues.set(i, 0, currentVal[i]);
            }
            mean = new Matrix(new double[m], m);
            cov = new Matrix(new double[m][m]);
            // mean initialized to 0 automatically
            // now initialize cov
            cov.setMatrix(0, m - 1, 0, m - 1, sigma);

            sentIndex = new int[m];
            lastIndex = new int[m];
            Helper.reset(sentIndex, 0);
            Helper.reset(lastIndex, 0);

            stat_sentCount += m;
            stat_tx += 1;

            return sentIndex;
        }

        Matrix currentValues = new Matrix(currentVal, m);

        // move forward one epoch
        forward();
        // cov.print(0, 7);

        // prediction and bound check
        Matrix prediction = predict(mean, cov, sentIndex, lastIndex, sentValues);

        if (isBounded(prediction, currentValues, epsilon)) {
            // System.out.println("suppressed");
            return null;
        }

        /*
         * subset selection
         */
        int[] newSentIndex = subsetSelector.select(mean, cov, sentValues, currentValues,
                epsilon);
        int subsetSize = newSentIndex.length;
        // for (int j = 0; j < newSentIndex.length; j++) {
        // if (newSentIndex[j] >= m) {
        // subsetSize++;
        // }
        // }
        stat_sentCount += subsetSize;
        stat_tx += (subsetSize !=0 ?1:0);

        // compact mean and cov by dropping useless entries
        // newly selected indices overwrite previous ones
        // e.g. suppose newSentIndex=[1,2], m=4, then newIndex=[0,3,4,5,6,7]
        // and sentIndex becomes [0,3,4,1] while lastIndex=[2,3,4,5]
        int[] newIndex = new int[dim - subsetSize];
        Vector<Integer> vec = new Vector<Integer>();
        for (int j = 0; j < dim; j++) {
            vec.add(j);
        }
        for (int j = subsetSize - 1; j > -1; j--) {
            vec.remove(newSentIndex[j]);
        }
        for (int j = 0; j < vec.size(); j++) {
            newIndex[j] = vec.get(j);
        // int k = 0;
        // for (int j = 0; j < newSentIndex.length; j++) {
        // if (newSentIndex[j] < m)
        // newIndex[k++] = newSentIndex[j];
        // }
        // for (int j = m; j < dim; j++)
        // newIndex[k++] = j;
        }
        mean = mean.getMatrix(newIndex, 0, 0);
        cov = cov.getMatrix(newIndex, newIndex);
        dim -= subsetSize;
        Helper.reset(lastIndex, m - subsetSize);

        int x = 0; // how many have been dropped so far

        int k = 0;
        for (int j = 0; j < sentIndex.length; j++) {
            if (k < subsetSize && sentIndex[j] == newSentIndex[k]) { // intersect
                // of
                // sentIndex
                // and
                // predictIndex

                x++;
                k++;
                sentIndex[j] += (m - subsetSize);
                sentValues.set(j, 0, currentValues.get(j, 0));
            } else {
                sentIndex[j] -= x;
            }
        }
        // int x = 0;
        // for (int j = 0; j < newSentIndex.length; j++) {
        // if (newSentIndex[j] >= m) { // intersect of sentIndex and
        // // predictIndex
        // x++;
        // newSentIndex[j] -= subsetSize;
        // sentValues.set(j, 0, currentValues.get(j, 0));
        // } else {
        // newSentIndex[j] -= x;
        // }
        // }
        // sentIndex = newSentIndex;
        return newSentIndex;
    }

    private boolean isBounded(Matrix a, Matrix b, double e) {
        return (a.minus(b).normInf() <= e);
    }

    /**
     * Deletes -1 from an array
     * 
     * @param a
     *            Array
     * @return Result
     */
    static int[] pack(int[] a) {
        int n = 0;
        // int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != -1) {
                n++;
            // no -1s in a
            }
        }
        if (n == a.length) {
            return a;
        }
        int j = 0;
        int[] l = new int[n];
        for (int i = 0; i < a.length; i++) {
            if (a[i] != -1) {
                l[j++] = a[i];
            }
        }
        return l;
    }

    /**
     * Predicts other readings when sending a subset.
     * 
     * @param mean
     * @param cov
     * @param sentIndex
     *            which elements to be sent
     * @param predictIndex
     *            which elements to be predicted
     * @param sentValues
     *            values of sent elements
     * @return the prediction matrix (a column vector)
     */
    @Override
    public Matrix predict(Matrix mean, Matrix cov, int[] sentIndex, int[] predictIndex,
            Matrix sentValues) {
        predictIndex = pack(predictIndex); // remove -1 elements

        Matrix predictMean = mean.getMatrix(predictIndex, 0, 0);
        predictMean.plusEquals(cov.getMatrix(predictIndex, sentIndex).times(
                cov.getMatrix(sentIndex, sentIndex).inverse()).times(
                sentValues.minus(mean.getMatrix(sentIndex, 0, 0))));
        return predictMean;
    }

    public double getEpsilon() {
        return epsilon;
    }

    public void setEpsilon(double epsilon) {
        this.epsilon = epsilon;
    }

    /**
     * Updates the model after receiving a subset of values.
     * 
     * @param content
     */
    public void update(int epoch, List<IndexValuePair> content) {
        // all values should be transmitted at epoch 0
        if (epoch == 0) {
            dim = m;
            sentIndex = new int[m];
            lastIndex = new int[m];
            Helper.reset(sentIndex, 0);
            Helper.reset(lastIndex, 0);

            sentValues = new Matrix(m, 1); // last sent value for each node

            for (IndexValuePair p : content) {
                sentIndex[p.index] = p.index;
                sentValues.set(p.index, 0, p.value);
            }
            // END = 2 * m - 1;
            mean = new Matrix(new double[m], m);
            cov = new Matrix(new double[m][m]);
            // mean initialized to 0 automatically
            // now initialize cov
            cov.setMatrix(0, m - 1, 0, m - 1, sigma);

            return;
        }

        // move forward and update various matrices
        forward();
        // cov.print(0, 7);

        int size = content == null ? 0 : content.size();

        if (size == 0) {
            // empty message
            // sentIndex and lastIndex remain intact
        } else {
            // for (IndexValuePair p : content) {
            // predictIndex[p.index] = -1;
            // sentIndex[p.index] += m;
            // sentValues.set(p.index, 0, p.value);
            // }
            int[] newIndex = new int[dim - size];
            Vector<Integer> vec = new Vector<Integer>();
            for (int j = 0; j < dim; j++) {
                vec.add(j);
            }
            for (int j = size - 1; j > -1; j--) {
                vec.remove(content.get(j).index);
            }
            for (int j = 0; j < vec.size(); j++) {
                newIndex[j] = vec.get(j);
            }
            mean = mean.getMatrix(newIndex, 0, 0);
            cov = cov.getMatrix(newIndex, newIndex);
            dim -= size;
            Helper.reset(lastIndex, m - size);
            int x = 0; // how many have been dropped so far

            int k = 0;
            for (int j = 0; j < sentIndex.length; j++) {
                if (k < size && sentIndex[j] == content.get(k).index) { // intersect
                    // of
                    // sentIndex
                    // and
                    // predictIndex

                    sentIndex[j] += (m - size);
                    sentValues.set(j, 0, content.get(k).value);
                    x++;
                    k++;
                } else {
                    sentIndex[j] -= x;
                }
            }

        }

        // check if we have sth to predict
        int[] predictIndex = new int[m];
        Helper.reset(predictIndex, dim - m);
        if (size < m) {
            Matrix prediction = predict(mean, cov, sentIndex, predictIndex, sentValues);

            if (size != 0) {
                for (IndexValuePair p : content) {
                    prediction.set(p.index, 0, p.value);
                }
            }
        }

    // update mean and cov to drop useless terms
    // dim -=
    // System.out.println("===== prediction @ base station");
    // prediction.print(0, 7);

    }
}

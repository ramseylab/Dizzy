package org.systemsbiology.chem;

import org.systemsbiology.math.Value;
import org.systemsbiology.math.Symbol;
import org.systemsbiology.math.SymbolEvaluator;
import org.systemsbiology.math.MathFunctions;
import org.systemsbiology.util.DataNotFoundException;
import org.systemsbiology.util.IAliasableClass;
import org.systemsbiology.util.DebugUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

/**
 * Simulates the dynamics of a set of coupled chemical reactions
 * described by {@link Reaction} objects using the Runge-Kutta
 * algorithm (fifth order with adaptive step-size control).
 *
 * @author Stephen Ramsey
 */
public class DeterministicSimulator extends Simulator implements IAliasableClass, ISimulator
{
    public static final String CLASS_ALIAS = "ODE-RK5-adaptive";

    private static final double TINY = 1.0e-30;
    private static final double SAFETY = 0.9;
    private static final double PGROW = -0.20;
    private static final double PSHRINK = -0.25;
    private static final double ERRCON = 6.0e-4;
    private static final double MAX_FRACTIONAL_ERROR = 0.001;

    class RKScratchPad
    {
        double []k1;
        double []k2;
        double []k3;
        double []k4;
        double []ysav;
        double []yscratch;
        double []y1;
        double []y2;
        double []yscale;
        double []dydt;
        double stepSize;
        double maxStepSize;

        public RKScratchPad(int pNumVariables)
        {
            k1 = new double[pNumVariables];
            k2 = new double[pNumVariables];
            k3 = new double[pNumVariables];
            k4 = new double[pNumVariables];
            ysav = new double[pNumVariables];
            yscratch = new double[pNumVariables];
            y1 = new double[pNumVariables];
            y2 = new double[pNumVariables];
            yscale = new double[pNumVariables];
            dydt = new double[pNumVariables];
            clear();
        }

        public void clear()
        {
            MathFunctions.vectorZeroElements(k1);
            MathFunctions.vectorZeroElements(k2);
            MathFunctions.vectorZeroElements(k3);
            MathFunctions.vectorZeroElements(k4);
            MathFunctions.vectorZeroElements(ysav);
            MathFunctions.vectorZeroElements(yscratch);
            MathFunctions.vectorZeroElements(y1);
            MathFunctions.vectorZeroElements(y2);
            MathFunctions.vectorZeroElements(yscale);
            MathFunctions.vectorZeroElements(dydt);
            stepSize = 0.0;
            maxStepSize = 0.0;
        }
    }

    private RKScratchPad mRKScratchPad;
    
    private static final void computeDerivative(SpeciesRateFactorEvaluator pSpeciesRateFactorEvaluator,
                                                SymbolEvaluatorChemSimulation pSymbolEvaluator,
                                                Reaction []pReactions,
                                                double []pReactionProbabilities,
                                                double []pTempDynamicSymbolValues,
                                                double []pDynamicSymbolDerivatives) throws DataNotFoundException
    {
        computeReactionProbabilities(pSpeciesRateFactorEvaluator,
                                     pSymbolEvaluator,
                                     pReactionProbabilities,
                                     pReactions);

        int numReactions = pReactions.length;
        Reaction reaction = null;
        double reactionRate = 0.0;

        MathFunctions.vectorZeroElements(pDynamicSymbolDerivatives);

        for(int reactionCtr = numReactions; --reactionCtr >= 0; )
        {
            reaction = pReactions[reactionCtr];
            reactionRate = pReactionProbabilities[reactionCtr];

            double []symbolAdjustmentVector = reaction.getDynamicSymbolAdjustmentVector();
            
            // we want to multiply this vector by the reaction rate and add it to the derivative
            MathFunctions.vectorScalarMultiply(symbolAdjustmentVector, reactionRate, pTempDynamicSymbolValues);
            
            MathFunctions.vectorAdd(pTempDynamicSymbolValues, pDynamicSymbolDerivatives, pDynamicSymbolDerivatives);
        }
    }

    private static final double iterate(SpeciesRateFactorEvaluator pSpeciesRateFactorEvaluator,
                                        SymbolEvaluatorChemSimulation pSymbolEvaluator,
                                        Reaction []pReactions,
                                        double []pReactionProbabilities,
                                        RKScratchPad pRKScratchPad,
                                        double pMaxFractionalError,
                                        double []pDynamicSymbolValues,
                                        double []pNewDynamicSymbolValues) throws DataNotFoundException
    {
        double stepSize = pRKScratchPad.stepSize;

        double nextStepSize = adaptiveStep(pSpeciesRateFactorEvaluator,
                                           pSymbolEvaluator,
                                           pReactions,
                                           pReactionProbabilities,
                                           pRKScratchPad,
                                           pMaxFractionalError,
                                           stepSize,
                                           pDynamicSymbolValues,
                                           pNewDynamicSymbolValues);

        pRKScratchPad.stepSize = nextStepSize;

        return(pSymbolEvaluator.getTime());
    }

    private static final double rkqc(SpeciesRateFactorEvaluator pSpeciesRateFactorEvaluator,
                                     SymbolEvaluatorChemSimulation pSymbolEvaluator,
                                     Reaction []pReactions,
                                     double []pReactionProbabilities,
                                     RKScratchPad pRKScratchPad,
                                     double pTimeStepSize,
                                     double []pDynamicSymbolValueScales,
                                     double []pDynamicSymbolValues,
                                     double []pNewDynamicSymbolValues) throws DataNotFoundException
    {
        double time = pSymbolEvaluator.getTime();
        int numDynamicSymbols = pDynamicSymbolValues.length;

        rk4step(pSpeciesRateFactorEvaluator,
                pSymbolEvaluator,
                pReactions,
                pReactionProbabilities,
                pRKScratchPad,
                pTimeStepSize,
                pDynamicSymbolValues,
                pNewDynamicSymbolValues);

        double halfStepSize = pTimeStepSize / 2.0;
        double timePlusHalfStep = time + halfStepSize;

        double []y1 = pRKScratchPad.y1;

        rk4step(pSpeciesRateFactorEvaluator,
                pSymbolEvaluator,
                pReactions,
                pReactionProbabilities,
                pRKScratchPad,
                halfStepSize,
                pDynamicSymbolValues,
                y1);
        
        double []ysav = pRKScratchPad.ysav;
        System.arraycopy(pDynamicSymbolValues, 0, ysav, 0, numDynamicSymbols);
        System.arraycopy(y1, 0, pDynamicSymbolValues, 0, numDynamicSymbols);
        
        pSymbolEvaluator.setTime(timePlusHalfStep);

        double []y2 = pRKScratchPad.y2;

        rk4step(pSpeciesRateFactorEvaluator,
                pSymbolEvaluator,
                pReactions,
                pReactionProbabilities,
                pRKScratchPad,
                halfStepSize,
                pDynamicSymbolValues,
                y2);

        System.arraycopy(ysav, 0, pDynamicSymbolValues, 0, numDynamicSymbols);
        pSymbolEvaluator.setTime(time);

        double aggregateError = 0.0;
        double singleError = 0.0;
        // compute error
        for(int symCtr = numDynamicSymbols; --symCtr >= 0; )
        {
            // FOR DEBUGGING ONLY:
//            System.out.println("y1[" + symCtr + "]: " + pNewDynamicSymbolValues[symCtr] + "; y2[" + symCtr + "]: " + y2[symCtr] + "; yscal[" + symCtr + "]: " + pDynamicSymbolValueScales[symCtr]);
            singleError = Math.abs(pNewDynamicSymbolValues[symCtr] - y2[symCtr])/pDynamicSymbolValueScales[symCtr];
            aggregateError += singleError;
        }

        return(aggregateError);
    }

    private static final double adaptiveStep(SpeciesRateFactorEvaluator pSpeciesRateFactorEvaluator,
                                             SymbolEvaluatorChemSimulation pSymbolEvaluator,
                                             Reaction []pReactions,
                                             double []pReactionProbabilities,
                                             RKScratchPad pRKScratchPad,
                                             double pMaxFractionalError,
                                             double pTimeStepSize,
                                             double []pDynamicSymbolValues,
                                             double []pNewDynamicSymbolValues) throws DataNotFoundException
    {
        double []dydt = pRKScratchPad.dydt;
        double []yscratch = pRKScratchPad.yscratch;
        double []yscale = pRKScratchPad.yscale;

        computeDerivative(pSpeciesRateFactorEvaluator,
                          pSymbolEvaluator,
                          pReactions,
                          pReactionProbabilities,
                          yscratch,
                          dydt);

        int numDynamicSymbols = pDynamicSymbolValues.length;
        double dydtn = 0.0;
        double yn = 0.0;

        for(int symCtr = numDynamicSymbols; --symCtr >= 0; )
        {
            dydtn = dydt[symCtr];
            yn = pDynamicSymbolValues[symCtr];
            yscale[symCtr] = Math.abs(yn) + Math.abs(dydtn * pTimeStepSize) + TINY;
        }
        
        double aggregateError = 0.0;
        double errRatio = 0.0;
        double stepSize = pTimeStepSize;

        double time = pSymbolEvaluator.getTime();

        do
        {
            aggregateError = rkqc(pSpeciesRateFactorEvaluator,
                                  pSymbolEvaluator,
                                  pReactions,
                                  pReactionProbabilities,
                                  pRKScratchPad,
                                  stepSize,
                                  yscale,
                                  pDynamicSymbolValues,
                                  pNewDynamicSymbolValues);

            errRatio = aggregateError / pMaxFractionalError ;
// FOR DEBUGGING ONLY:
//            System.out.println("time: " + time + "; stepsize: " + stepSize + "; aggregateError: " + aggregateError + "; errRatio: " + errRatio);
            
            if(errRatio > 1.0)
            {
                // error is too big; need to decrease the step size
                stepSize *= SAFETY * Math.exp(PSHRINK * Math.log(errRatio));
            }
            else
            {
                break;
            }

            // decrease step size
        }
        while(true);
        
        pSymbolEvaluator.setTime(time + stepSize);

        double nextStepSize = 0.0;

        if(errRatio > ERRCON)
        {
            nextStepSize =  SAFETY * stepSize * Math.exp(PGROW * Math.log(errRatio));
        }
        else
        {
            nextStepSize = 4.0 * stepSize;
        }

        double maxStepSize = pRKScratchPad.maxStepSize;
        if(nextStepSize > maxStepSize)
        {
            nextStepSize = maxStepSize;
        }

        return(nextStepSize);
    }

    private static final void rk4step(SpeciesRateFactorEvaluator pSpeciesRateFactorEvaluator,
                                      SymbolEvaluatorChemSimulation pSymbolEvaluator,
                                      Reaction []pReactions,
                                      double []pReactionProbabilities,
                                      RKScratchPad pRKScratchPad,
                                      double pTimeStepSize,
                                      double []pDynamicSymbolValues,
                                      double []pNewDynamicSymbolValues) throws DataNotFoundException
    {
        double time = pSymbolEvaluator.getTime();

        double []k1 = pRKScratchPad.k1;  // note:  our "k1" is equivalent to 0.5 times the "k1" in numerical recipes
        int numVars = k1.length;

        double []y = pDynamicSymbolValues;
        double []ysav = pRKScratchPad.ysav;
        double []yscratch = pRKScratchPad.yscratch;

        double halfStep = pTimeStepSize / 2.0;
        double timePlusHalfStep = time + halfStep;

        // save a copy of the initial y values
        System.arraycopy(y, 0, ysav, 0, numVars);

        computeDerivative(pSpeciesRateFactorEvaluator,
                          pSymbolEvaluator,
                          pReactions,
                          pReactionProbabilities,
                          yscratch,
                          k1);
        MathFunctions.vectorScalarMultiply(k1, halfStep, k1);
        // now, k1 contains  h * f'(t, y)/2.0

        // set the y values to "y + k1"
        MathFunctions.vectorAdd(ysav, k1, y);

        // set the time to "t + h/2"
        pSymbolEvaluator.setTime(timePlusHalfStep);

        double []k2 = pRKScratchPad.k2;
        computeDerivative(pSpeciesRateFactorEvaluator,
                          pSymbolEvaluator,
                          pReactions,
                          pReactionProbabilities,
                          yscratch,
                          k2);
        MathFunctions.vectorScalarMultiply(k2, halfStep, k2);

        MathFunctions.vectorAdd(ysav, k2, y);
        // y now contains "y + k2"

        double []k3 = pRKScratchPad.k3;
        computeDerivative(pSpeciesRateFactorEvaluator,
                          pSymbolEvaluator,
                          pReactions,
                          pReactionProbabilities,
                          yscratch,
                          k3);
        MathFunctions.vectorScalarMultiply(k3, pTimeStepSize, k3);
        // k3 now contains h * f'(t + h/2, y + k2)

        MathFunctions.vectorAdd(ysav, k3, y);
        // y now contains  "y + k3"

        double []k4 = pRKScratchPad.k4;


        // set time to "t + h"
        double pNextTime = time + pTimeStepSize;
        pSymbolEvaluator.setTime(pNextTime);

        computeDerivative(pSpeciesRateFactorEvaluator,
                          pSymbolEvaluator,
                          pReactions,
                          pReactionProbabilities,
                          yscratch,
                          k4);
        MathFunctions.vectorScalarMultiply(k4, pTimeStepSize, k4);
        // k4 now contains h * f'(t + h, y + k3)


        for(int ctr = numVars; --ctr >= 0; )
        {
            pNewDynamicSymbolValues[ctr] = ysav[ctr] + 
                                           (k1[ctr]/3.0) +
                                           (2.0 * k2[ctr] / 3.0) +
                                           (k3[ctr] / 3.0) +
                                           (k4[ctr] / 6.0);
        }

        MathFunctions.vectorZeroNegativeElements(pNewDynamicSymbolValues);

        // restore to previous values; allow the driver to update
        System.arraycopy(ysav, 0, y, 0, numVars);
        pSymbolEvaluator.setTime(time);
    }

    public void initialize(Model pModel, SimulationController pSimulationController) throws DataNotFoundException
    {
        initializeSimulator(pModel, pSimulationController);
        int numDynamicSymbols = mDynamicSymbolValues.length;
        mRKScratchPad = new RKScratchPad(numDynamicSymbols);
    }

    public void simulate(double pStartTime, 
                         double pEndTime,
                         int pNumTimePoints,
                         int pNumSteps,
                         String []pRequestedSymbolNames,
                         double []pRetTimeValues,
                         Object []pRetSymbolValues) throws DataNotFoundException, IllegalStateException
    {
        if(! mInitialized)
        {
            throw new IllegalStateException("simulator has not been initialized yet");
        }

        if(pNumSteps <= 0)
        {
            throw new IllegalArgumentException("illegal value for number of steps");
        }

        if(pNumTimePoints <= 0)
        {
            throw new IllegalArgumentException("number of time points must be nonnegative");
        }

        if(pStartTime > pEndTime)
        {
            throw new IllegalArgumentException("end time must come after start time");
        }
        
        if(pRetTimeValues.length != pNumTimePoints)
        {
            throw new IllegalArgumentException("illegal length of pRetTimeValues array");
        }

        if(pRetSymbolValues.length != pNumTimePoints)
        {
            throw new IllegalArgumentException("illegal length of pRetSymbolValues array");
        }

        SpeciesRateFactorEvaluator speciesRateFactorEvaluator = mSpeciesRateFactorEvaluator;
        SymbolEvaluatorChemSimulation symbolEvaluator = mSymbolEvaluator;
        double []reactionProbabilities = mReactionProbabilities;
        Reaction []reactions = mReactions;
        double []dynamicSymbolValues = mDynamicSymbolValues;        
        int numDynamicSymbolValues = dynamicSymbolValues.length;
        HashMap symbolMap = mSymbolMap;

        double []timesArray = new double[pNumTimePoints];

        prepareTimesArray(pStartTime, 
                          pEndTime,
                          pNumTimePoints,
                          timesArray);        

        Symbol []requestedSymbols = prepareRequestedSymbolArray(symbolMap,
                                                                pRequestedSymbolNames);

        int numRequestedSymbols = requestedSymbols.length;

        double []newSimulationSymbolValues = new double[numDynamicSymbolValues];

        double time = pStartTime;
        
        prepareForSimulation(time);
        
        // set "last" values for dynamic symbols to be same as initial values
        System.arraycopy(dynamicSymbolValues, 0, newSimulationSymbolValues, 0, numDynamicSymbolValues);

        int timeCtr = 0;
            
//            int numIterations = 0;

        if(pNumSteps < pNumTimePoints)
        {
            pNumSteps = pNumTimePoints;
        }
        double maxFractionalError = 1.0 / ((double) pNumSteps);
        if(maxFractionalError > MAX_FRACTIONAL_ERROR)
        {
            maxFractionalError = MAX_FRACTIONAL_ERROR;
        }
        double maxStepSize = (pEndTime - pStartTime) / ((double) pNumSteps);
        double stepSize = maxStepSize / 5.0;
        RKScratchPad scratchPad = mRKScratchPad;
        scratchPad.clear();
        scratchPad.stepSize = stepSize;
        scratchPad.maxStepSize = maxStepSize;

        boolean isCancelled = false;

        while(pNumTimePoints - timeCtr > 0)
        {
            time = iterate(speciesRateFactorEvaluator,
                           symbolEvaluator,
                           reactions,
                           reactionProbabilities,
                           scratchPad,
                           maxFractionalError,
                           dynamicSymbolValues,
                           newSimulationSymbolValues);
                
//                ++numIterations;

            if(time > timesArray[timeCtr])
            {
                timeCtr = addRequestedSymbolValues(time,
                                                   timeCtr,
                                                   requestedSymbols,
                                                   symbolEvaluator,
                                                   timesArray,
                                                   pRetSymbolValues);

                isCancelled = checkSimulationControllerStatus();
                if(isCancelled)
                {
                    break;
                }
            }

            System.arraycopy(newSimulationSymbolValues, 0, dynamicSymbolValues, 0, numDynamicSymbolValues);


        }

//        System.out.println("number of iterations: " + numIterations);

        // copy array of time points 
        System.arraycopy(timesArray, 0, pRetTimeValues, 0, timeCtr);
        
    }
}
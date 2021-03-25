/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.ml.optim.aggregator

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.ml.feature.InstanceBlock
import org.apache.spark.ml.impl.Utils
import org.apache.spark.ml.linalg._

/**
 * BinaryLogisticBlockAggregator computes the gradient and loss used in binary logistic
 * classification for blocks in sparse or dense matrix in an online fashion.
 *
 * Two BinaryLogisticBlockAggregator can be merged together to have a summary of loss and
 * gradient of the corresponding joint dataset.
 *
 * NOTE: The feature values are expected to already have be scaled (multiplied by bcInverseStd,
 * but NOT centered) before computation.
 *
 * @param bcCoefficients The coefficients corresponding to the features.
 * @param fitIntercept Whether to fit an intercept term.
 * @param fitWithMean Whether to center the data with mean before training, in a virtual way.
 *                    If true, we MUST adjust the intercept of both initial coefficients and
 *                    final solution in the caller.
 */
private[ml] class BinaryLogisticBlockAggregator(
    bcInverseStd: Broadcast[Array[Double]],
    bcScaledMean: Broadcast[Array[Double]],
    fitIntercept: Boolean,
    fitWithMean: Boolean)(bcCoefficients: Broadcast[Vector])
  extends DifferentiableLossAggregator[InstanceBlock, BinaryLogisticBlockAggregator]
  with Logging {

  if (fitWithMean) {
    require(fitIntercept, s"for training without intercept, should not center the vectors")
    require(bcScaledMean != null && bcScaledMean.value.length == bcInverseStd.value.length,
      "scaled means is required when center the vectors")
  }

  private val numFeatures = bcInverseStd.value.length
  protected override val dim: Int = bcCoefficients.value.size

  @transient private lazy val coefficientsArray = bcCoefficients.value match {
    case DenseVector(values) => values
    case _ => throw new IllegalArgumentException(s"coefficients only supports dense vector but " +
      s"got type ${bcCoefficients.value.getClass}.)")
  }

  @transient private lazy val linear = if (fitIntercept) {
    new DenseVector(coefficientsArray.take(numFeatures))
  } else {
    new DenseVector(coefficientsArray)
  }

  // pre-computed margin of an empty vector.
  // with this variable as an offset, for a sparse vector, we only need to
  // deal with non-zero values in prediction.
  private val marginOffset = if (fitWithMean) {
    coefficientsArray.last -
      BLAS.getBLAS(numFeatures).ddot(numFeatures, coefficientsArray, 1, bcScaledMean.value, 1)
  } else {
    Double.NaN
  }

  /**
   * Add a new training instance block to this BinaryLogisticBlockAggregator, and update the loss
   * and gradient of the objective function.
   *
   * @param block The instance block of data point to be added.
   * @return This BinaryLogisticBlockAggregator object.
   */
  def add(block: InstanceBlock): this.type = {
    require(block.matrix.isTransposed)
    require(numFeatures == block.numFeatures, s"Dimensions mismatch when adding new " +
      s"instance. Expecting $numFeatures but got ${block.numFeatures}.")
    require(block.weightIter.forall(_ >= 0),
      s"instance weights ${block.weightIter.mkString("[", ",", "]")} has to be >= 0.0")

    if (block.weightIter.forall(_ == 0)) return this
    val size = block.size

    // vec/arr here represents margins
    val vec = new DenseVector(Array.ofDim[Double](size))
    val arr = vec.values
    if (fitIntercept) {
      val offset = if (fitWithMean) marginOffset else coefficientsArray.last
      java.util.Arrays.fill(arr, offset)
    }
    BLAS.gemv(1.0, block.matrix, linear, 1.0, vec)

    // in-place convert margins to multiplier
    // then, vec/arr represents multiplier
    var localLossSum = 0.0
    var localWeightSum = 0.0
    var multiplierSum = 0.0
    var i = 0
    while (i < size) {
      val weight = block.getWeight(i)
      localWeightSum += weight
      if (weight > 0) {
        val label = block.getLabel(i)
        val margin = arr(i)
        if (label > 0) {
          // The following is equivalent to log(1 + exp(-margin)) but more numerically stable.
          localLossSum += weight * Utils.log1pExp(-margin)
        } else {
          localLossSum += weight * (Utils.log1pExp(-margin) + margin)
        }
        val multiplier = weight * (1.0 / (1.0 + math.exp(-margin)) - label)
        arr(i) = multiplier
        multiplierSum += multiplier
      } else { arr(i) = 0.0 }
      i += 1
    }
    lossSum += localLossSum
    weightSum += localWeightSum

    // predictions are all correct, no gradient signal
    if (arr.forall(_ == 0)) return this

    // update the linear part of gradientSumArray
    block.matrix match {
      case dm: DenseMatrix =>
        BLAS.nativeBLAS.dgemv("N", dm.numCols, dm.numRows, 1.0, dm.values, dm.numCols,
          vec.values, 1, 1.0, gradientSumArray, 1)

      case sm: SparseMatrix if fitIntercept =>
        val linearGradSumVec = new DenseVector(Array.ofDim[Double](numFeatures))
        BLAS.gemv(1.0, sm.transpose, vec, 0.0, linearGradSumVec)
        BLAS.getBLAS(numFeatures).daxpy(numFeatures, 1.0, linearGradSumVec.values, 1,
          gradientSumArray, 1)

      case sm: SparseMatrix if !fitIntercept =>
        val gradSumVec = new DenseVector(gradientSumArray)
        BLAS.gemv(1.0, sm.transpose, vec, 1.0, gradSumVec)

      case m =>
        throw new IllegalArgumentException(s"Unknown matrix type ${m.getClass}.")
    }

    if (fitWithMean) {
      // above update of the linear part of gradientSumArray does NOT take the centering
      // into account, here we need to adjust this part.
      BLAS.getBLAS(numFeatures).daxpy(numFeatures, -multiplierSum, bcScaledMean.value, 1,
        gradientSumArray, 1)
    }

    if (fitIntercept) {
      // update the intercept part of gradientSumArray
      gradientSumArray(numFeatures) += multiplierSum
    }

    this
  }
}

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

package org.apache.spark.ml.classification

import org.apache.hadoop.fs.Path

import org.apache.spark.annotation.Since
import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.param._
import org.apache.spark.ml.regression.{FactorizationMachines, FactorizationMachinesParams}
import org.apache.spark.ml.regression.FactorizationMachines._
import org.apache.spark.ml.util._
import org.apache.spark.ml.util.Instrumentation.instrumented
import org.apache.spark.mllib.linalg.{Vector => OldVector}
import org.apache.spark.mllib.linalg.VectorImplicits._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, Row}
import org.apache.spark.storage.StorageLevel

/**
 * Params for FMClassifier.
 */
private[classification] trait FMClassifierParams extends ProbabilisticClassifierParams
  with FactorizationMachinesParams {
}

/**
 * Factorization Machines learning algorithm for classification.
 * It supports normal gradient descent and AdamW solver.
 *
 * The implementation is based upon:
 * <a href="https://www.csie.ntu.edu.tw/~b97053/paper/Rendle2010FM.pdf">
 * S. Rendle. "Factorization machines" 2010</a>.
 *
 * FM is able to estimate interactions even in problems with huge sparsity
 * (like advertising and recommendation system).
 * FM formula is:
 * <blockquote>
 *   $$
 *   \begin{align}
 *   y = \sigma\left( w_0 + \sum\limits^n_{i-1} w_i x_i +
 *     \sum\limits^n_{i=1} \sum\limits^n_{j=i+1} \langle v_i, v_j \rangle x_i x_j \right)
 *   \end{align}
 *   $$
 * </blockquote>
 * First two terms denote global bias and linear term (as same as linear regression),
 * and last term denotes pairwise interactions term. v_i describes the i-th variable
 * with k factors.
 *
 * FM classification model uses logistic loss which can be solved by gradient descent method, and
 * regularization terms like L2 are usually added to the loss function to prevent overfitting.
 *
 * @note Multiclass labels are not currently supported.
 */
@Since("3.0.0")
class FMClassifier @Since("3.0.0") (
    @Since("3.0.0") override val uid: String)
  extends ProbabilisticClassifier[Vector, FMClassifier, FMClassificationModel]
  with FactorizationMachines with FMClassifierParams with DefaultParamsWritable with Logging {

  @Since("3.0.0")
  def this() = this(Identifiable.randomUID("fmc"))

  /**
   * Set the dimensionality of the factors.
   * Default is 8.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setFactorSize(value: Int): this.type = set(factorSize, value)
  setDefault(factorSize -> 8)

  /**
   * Set whether to fit intercept term.
   * Default is true.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setFitIntercept(value: Boolean): this.type = set(fitIntercept, value)
  setDefault(fitIntercept -> true)

  /**
   * Set whether to fit linear term.
   * Default is true.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setFitLinear(value: Boolean): this.type = set(fitLinear, value)
  setDefault(fitLinear -> true)

  /**
   * Set the L2 regularization parameter.
   * Default is 0.0.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setRegParam(value: Double): this.type = set(regParam, value)
  setDefault(regParam -> 0.0)

  /**
   * Set the mini-batch fraction parameter.
   * Default is 1.0.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setMiniBatchFraction(value: Double): this.type = set(miniBatchFraction, value)
  setDefault(miniBatchFraction -> 1.0)

  /**
   * Set the standard deviation of initial coefficients.
   * Default is 0.01.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setInitStd(value: Double): this.type = set(initStd, value)
  setDefault(initStd -> 0.01)

  /**
   * Set the maximum number of iterations.
   * Default is 100.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setMaxIter(value: Int): this.type = set(maxIter, value)
  setDefault(maxIter -> 100)

  /**
   * Set the initial step size for the first step (like learning rate).
   * Default is 1.0.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setStepSize(value: Double): this.type = set(stepSize, value)
  setDefault(stepSize -> 1.0)

  /**
   * Set the convergence tolerance of iterations.
   * Default is 1E-6.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setTol(value: Double): this.type = set(tol, value)
  setDefault(tol -> 1E-6)

  /**
   * Set the solver algorithm used for optimization.
   * Supported options: "gd", "adamW".
   * Default: "adamW"
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setSolver(value: String): this.type = set(solver, value)
  setDefault(solver -> AdamW)

  /**
   * Set the random seed for weight initialization.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setSeed(value: Long): this.type = set(seed, value)

  override protected def train(
      dataset: Dataset[_]
    ): FMClassificationModel = instrumented { instr =>

    val numClasses = 2
    if (isDefined(thresholds)) {
      require($(thresholds).length == numClasses, this.getClass.getSimpleName +
        ".train() called with non-matching numClasses and thresholds.length." +
        s" numClasses=$numClasses, but thresholds has length ${$(thresholds).length}")
    }

    instr.logPipelineStage(this)
    instr.logDataset(dataset)
    instr.logParams(this, factorSize, fitIntercept, fitLinear, regParam,
      miniBatchFraction, initStd, maxIter, stepSize, tol, solver)
    instr.logNumClasses(numClasses)

    val numFeatures = MetadataUtils.getNumFeatures(dataset, $(featuresCol))
    instr.logNumFeatures(numFeatures)

    val handlePersistence = dataset.storageLevel == StorageLevel.NONE
    val labeledPoint = extractLabeledPoints(dataset, numClasses)
    val data: RDD[(Double, OldVector)] = labeledPoint.map(x => (x.label, x.features))

    if (handlePersistence) data.persist(StorageLevel.MEMORY_AND_DISK)

    val coefficients = trainImpl(data, numFeatures, LogisticLoss)

    val (intercept, linear, factors) = splitCoefficients(
      coefficients, numFeatures, $(factorSize), $(fitIntercept), $(fitLinear))

    if (handlePersistence) data.unpersist()

    copyValues(new FMClassificationModel(uid, intercept, linear, factors))
  }

  @Since("3.0.0")
  override def copy(extra: ParamMap): FMClassifier = defaultCopy(extra)
}

@Since("3.0.0")
object FMClassifier extends DefaultParamsReadable[FMClassifier] {

  @Since("3.0.0")
  override def load(path: String): FMClassifier = super.load(path)
}

/**
 * Model produced by [[FMClassifier]]
 */
@Since("3.0.0")
class FMClassificationModel private[classification] (
  @Since("3.0.0") override val uid: String,
  @Since("3.0.0") val intercept: Double,
  @Since("3.0.0") val linear: Vector,
  @Since("3.0.0") val factors: Matrix)
  extends ProbabilisticClassificationModel[Vector, FMClassificationModel]
    with FMClassifierParams with MLWritable {

  @Since("3.0.0")
  override val numClasses: Int = 2

  @Since("3.0.0")
  override val numFeatures: Int = linear.size

  @Since("3.0.0")
  override def predictRaw(features: Vector): Vector = {
    val rawPrediction = getRawPrediction(features, intercept, linear, factors)
    Vectors.dense(Array(-rawPrediction, rawPrediction))
  }

  override protected def raw2probabilityInPlace(rawPrediction: Vector): Vector = {
    rawPrediction match {
      case dv: DenseVector =>
        dv.values(1) = 1.0 / (1.0 + math.exp(-dv.values(1)))
        dv.values(0) = 1.0 - dv.values(1)
        dv
      case sv: SparseVector =>
        throw new RuntimeException("Unexpected error in FMClassificationModel:" +
          " raw2probabilityInPlace encountered SparseVector")
    }
  }

  @Since("3.0.0")
  override def copy(extra: ParamMap): FMClassificationModel = {
    copyValues(new FMClassificationModel(uid, intercept, linear, factors), extra)
  }

  @Since("3.0.0")
  override def write: MLWriter =
    new FMClassificationModel.FMClassificationModelWriter(this)

  override def toString: String = {
    s"FMClassificationModel: " +
      s"uid=${super.toString}, numClasses=$numClasses, numFeatures=$numFeatures, " +
      s"factorSize=${$(factorSize)}, fitLinear=${$(fitLinear)}, fitIntercept=${$(fitIntercept)}"
  }
}

@Since("3.0.0")
object FMClassificationModel extends MLReadable[FMClassificationModel] {

  @Since("3.0.0")
  override def read: MLReader[FMClassificationModel] = new FMClassificationModelReader

  @Since("3.0.0")
  override def load(path: String): FMClassificationModel = super.load(path)

  /** [[MLWriter]] instance for [[FMClassificationModel]] */
  private[FMClassificationModel] class FMClassificationModelWriter(
    instance: FMClassificationModel) extends MLWriter with Logging {

    private case class Data(
      intercept: Double,
      linear: Vector,
      factors: Matrix)

    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      val data = Data(instance.intercept, instance.linear, instance.factors)
      val dataPath = new Path(path, "data").toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.parquet(dataPath)
    }
  }

  private class FMClassificationModelReader extends MLReader[FMClassificationModel] {

    private val className = classOf[FMClassificationModel].getName

    override def load(path: String): FMClassificationModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
      val dataPath = new Path(path, "data").toString
      val data = sparkSession.read.format("parquet").load(dataPath)

      val Row(intercept: Double, linear: Vector, factors: Matrix) =
        data.select("intercept", "linear", "factors").head()
      val model = new FMClassificationModel(metadata.uid, intercept, linear, factors)
      metadata.getAndSetParams(model)
      model
    }
  }
}

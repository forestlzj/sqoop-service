package com.datamountaineer.ingestor

import com.datamountaineer.ingestor.conf.Configuration
import org.scalatest._

abstract class IngestorTestTrait extends WordSpec with Matchers with OptionValues with Inside with Inspectors with Configuration
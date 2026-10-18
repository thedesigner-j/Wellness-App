package com.javon.healthdash

import com.google.common.truth.Truth.assertThat
import com.javon.healthdash.data.ai.RecognitionValidator
import com.javon.healthdash.data.ai.RecognitionValidator.Result
import org.junit.Test

class RecognitionValidatorTest {

    @Test fun validPayload() {
        val r = RecognitionValidator.validate("""{"foods":[{"food_name":" brown rice, cooked ","portion_estimate":1,"portion_unit":"cup","confidence":"high"}]}""")
        assertThat(r).isInstanceOf(Result.Valid::class.java)
        assertThat((r as Result.Valid).foods.single().food_name).isEqualTo("brown rice, cooked")
    }

    @Test fun emptyListIsValid() {
        assertThat(RecognitionValidator.validate("""{"foods":[]}""")).isInstanceOf(Result.Valid::class.java)
    }

    @Test fun rejectsExtraFieldsLikeNutrition() {
        val r = RecognitionValidator.validate("""{"foods":[{"food_name":"rice","portion_estimate":1,"portion_unit":"cup","confidence":"high","carbs":45}]}""")
        assertThat(r).isInstanceOf(Result.Invalid::class.java)
    }

    @Test fun rejectsUnknownUnitAndConfidence() {
        assertThat(RecognitionValidator.validate("""{"foods":[{"food_name":"rice","portion_estimate":1,"portion_unit":"bowl","confidence":"high"}]}"""))
            .isInstanceOf(Result.Invalid::class.java)
        assertThat(RecognitionValidator.validate("""{"foods":[{"food_name":"rice","portion_estimate":1,"portion_unit":"cup","confidence":"sure"}]}"""))
            .isInstanceOf(Result.Invalid::class.java)
    }

    @Test fun rejectsOutOfRangePortions() {
        assertThat(RecognitionValidator.validate("""{"foods":[{"food_name":"rice","portion_estimate":0,"portion_unit":"cup","confidence":"high"}]}"""))
            .isInstanceOf(Result.Invalid::class.java)
        assertThat(RecognitionValidator.validate("""{"foods":[{"food_name":"rice","portion_estimate":9000,"portion_unit":"g","confidence":"high"}]}"""))
            .isInstanceOf(Result.Invalid::class.java)
    }

    @Test fun rejectsNutritionHiddenInName() {
        assertThat(RecognitionValidator.validate("""{"foods":[{"food_name":"rice 45g carbs","portion_estimate":1,"portion_unit":"cup","confidence":"high"}]}"""))
            .isInstanceOf(Result.Invalid::class.java)
    }

    @Test fun rejectsNonJson() {
        assertThat(RecognitionValidator.validate("Here are the foods: rice")).isInstanceOf(Result.Invalid::class.java)
        assertThat(RecognitionValidator.validate("""{"items":[]}""")).isInstanceOf(Result.Invalid::class.java)
    }
}

package martin.refactoring

import martin.compiler.AnalysisResult
import martin.refactoring.convert.*
import martin.refactoring.core.*
import martin.refactoring.extract.*
import martin.refactoring.restructure.*

/**
 * Central registry of all available refactorings.
 *
 * Each entry is a factory that takes an [AnalysisResult] and returns a [Refactoring].
 * This is the single source of truth for what refactorings Martin supports.
 */
object RefactoringRegistry {

    /** All refactoring factories. */
    val factories: List<(AnalysisResult) -> Refactoring> = listOf(
        // Core
        ::RenameRefactoring,
        ::InlineRefactoring,
        ::MoveRefactoring,
        ::SafeDeleteRefactoring,
        ::ChangeSignatureRefactoring,
        // Extract
        ::ExtractFunctionRefactoring,
        ::ExtractVariableRefactoring,
        ::ExtractConstantRefactoring,
        ::ExtractParameterRefactoring,
        ::ExtractInterfaceRefactoring,
        ::ExtractSuperclassRefactoring,
        // Convert
        ::ConvertToExpressionBodyRefactoring,
        ::ConvertToBlockBodyRefactoring,
        ::ConvertToDataClassRefactoring,
        ::ConvertToSealedClassRefactoring,
        ::ConvertToExtensionFunctionRefactoring,
        ::ConvertPropertyToFunctionRefactoring,
        ::AddNamedArgumentsRefactoring,
        // Restructure
        ::EncapsulateFieldRefactoring,
        ::PullUpMethodRefactoring,
        ::ReplaceConstructorWithFactoryRefactoring,
        ::IntroduceParameterObjectRefactoring,
        ::TypeMigrationRefactoring,
        ::MoveStatementsIntoFunctionRefactoring,
    )
}

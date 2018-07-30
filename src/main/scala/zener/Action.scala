package zener

/**
  * Base trait for actions.
  *
  * Actions are called from the view in order to trigger a change in
  * the application model. All application actions must extend this trait
  * in order to be considered for handling by the [[Circuit]].
  */
trait Action extends AnyRef {
  /**
    * Predicate to evaluate before calling the action.
    *
    * If this predicate is evaluated to be false, the
    * action will ``not`` be executed.
    */
  def conditionalTo: Boolean = true
}

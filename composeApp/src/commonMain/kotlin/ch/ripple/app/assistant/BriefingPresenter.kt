package ch.ripple.app.assistant

import ch.ripple.core.*

/** Only typed engine facts produce financial statements. No estimated or fabricated numbers. */
object BriefingPresenter {
    fun bounds(briefing: Briefing, french: Boolean): String? {
        val value = briefing.primary.facts.firstOrNull { it.key == "input:deposit" }?.value as? FactValue.MoneyRange ?: return null
        return if (french) "Limites explorées pour la garantie de loyer : ${value.low.format()} à ${value.high.format()}."
            else "Demo deposit bounds explored: ${value.low.format()} to ${value.high.format()}."
    }
    fun question(briefing: Briefing, french: Boolean): String? = when (briefing.followUp?.promptKey) {
        "question.deposit" -> if (french) "Savez-vous quel serait le montant de la garantie de loyer ? Vous pouvez répondre : je ne sais pas."
            else "Do you know how much the refundable rental deposit would be? You can also say: I'm not sure."
        else -> null
    }

    fun insight(briefing: Briefing, french: Boolean): String {
        val facts = briefing.primary.facts.associateBy { it.key }
        val floor = (facts["floor"]?.value as? FactValue.Money)?.amount?.format()
        val minimum = (facts["worst_min_cash"]?.value as? FactValue.Money)?.amount?.format()
        if (floor == null || minimum == null) return if (french) "Ce résultat nécessite une explication supplémentaire." else "This result needs an additional explanation."
        return when (briefing.primary.type) {
            InsightType.ROBUST_CONCLUSION -> if (french)
                "Dans tous les cas testés, la trésorerie reste au moins égale à votre réserve de $floor. Le solde le plus bas testé est de $minimum."
                else "In every tested case, cash stays at or above the buffer of $floor. The lowest tested cash balance is $minimum."
            InsightType.FRAGILE_PLAN, InsightType.LIQUIDITY_BREACH -> {
                val depends = Keys.LIMIT_DEPENDS_ON_INPUT in briefing.primary.limitationKeys
                if (french) (if (depends) "Le résultat dépend du montant encore inconnu. " else "") +
                    "Avec les hypothèses testées, au moins un cas passe sous la réserve de $floor. Le solde le plus bas testé est de $minimum."
                else (if (depends) "The result depends on the unknown input. " else "") +
                    "Under the tested assumptions, at least one case falls below the cash buffer of $floor. The lowest tested balance is $minimum."
            }
            else -> if (french) "Consultez les faits du modèle avant de décider." else "Review the model's facts before making a decision."
        }
    }

    fun qualifications(briefing: Briefing, french: Boolean): List<String> =
        (briefing.disclosureKeys + briefing.primary.limitationKeys + Keys.LIMIT_NOT_ADVICE).distinct().map { key ->
            val en = when (key) {
                Keys.DISCLOSURE_FICTIONAL -> "Fictional demo."
                Keys.DISCLOSURE_HYPOTHETICAL -> "Illustrative assumptions, not a prediction."
                Keys.DISCLOSURE_TESTED_CASES -> "Only the cases tested are covered."
                Keys.DISCLOSURE_CASH_ONLY -> "Month-end cash only; not a full financial plan."
                Keys.DISCLOSURE_UNKNOWN_BOUNDED -> "Unknown inputs use the stated demo bounds."
                Keys.DISCLOSURE_WHAT_IF -> "This is a hypothetical branch, not confirmed facts."
                Keys.LIMIT_NO_PROBABILITIES -> "No probabilities are estimated."
                Keys.LIMIT_DEPENDS_ON_INPUT -> "The conclusion depends on unknown inputs."
                Keys.LIMIT_ACTION_SET -> "Adjustments are limited to the modelled spending cuts."
                Keys.LIMIT_NOT_ADVICE -> "Not financial advice."
                Keys.LIMIT_QUESTION_HEURISTIC -> "The order of questions is a model choice."
                else -> "An additional model limitation requires review."
            }
            if (!french) en else when (key) {
                Keys.DISCLOSURE_FICTIONAL -> "Exemple fictif."
                Keys.DISCLOSURE_HYPOTHETICAL -> "Hypothèses illustratives, pas une prévision."
                Keys.DISCLOSURE_TESTED_CASES -> "Seuls les cas testés sont couverts."
                Keys.DISCLOSURE_CASH_ONLY -> "Trésorerie en fin de mois, pas un plan financier complet."
                Keys.DISCLOSURE_UNKNOWN_BOUNDED -> "Les données inconnues utilisent les limites de l'exemple."
                Keys.DISCLOSURE_WHAT_IF -> "Cette branche est hypothétique, non confirmée."
                Keys.LIMIT_NO_PROBABILITIES -> "Aucune probabilité n'est estimée."
                Keys.LIMIT_DEPENDS_ON_INPUT -> "La conclusion dépend de données inconnues."
                Keys.LIMIT_ACTION_SET -> "Les ajustements sont limités aux réductions de dépenses modélisées."
                Keys.LIMIT_NOT_ADVICE -> "Ce n'est pas un conseil financier."
                Keys.LIMIT_QUESTION_HEURISTIC -> "L'ordre des questions est un choix du modèle."
                else -> "Une limite supplémentaire du modèle doit être examinée."
            }
        }
}

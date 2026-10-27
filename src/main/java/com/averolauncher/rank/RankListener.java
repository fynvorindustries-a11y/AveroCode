package com.averolauncher.rank;

/** Callbacks from {@link RankService}. Invoked on background threads: UI code must hop to the FX thread. */
public interface RankListener {
    default void onChanged(RankSnapshot snapshot) { }
    default void onPromotion(RankStanding to) { }
    default void onQuestCompleted(QuestView quest) { }
}

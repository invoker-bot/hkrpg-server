package org.hkrpg.server.gameplay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoryTokenReplayTest {
  final SigninGiftReplayTest fixture=new SigninGiftReplayTest(){
    @Override String profileResource(){return "/gameplay/story-token-replay-4.6.json";}
  };
  @BeforeEach void prepare()throws Exception{fixture.prepare();}
  @AfterEach void cleanup(){fixture.restore();}
  @ParameterizedTest @ValueSource(ints={10093,10125,10141})
  void eachEditionHasItsOwnOriginalAndTwoReplayClaimCycles(int id){
    fixture.originalAndTwoRerunsRetainAllSourceDaysRewardsAndOldSaves(id);
  }
}

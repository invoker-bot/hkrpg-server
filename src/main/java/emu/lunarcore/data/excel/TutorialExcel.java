package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = {"TutorialData.json"})
public class TutorialExcel extends GameResource {
    private int TutorialID;
    private List<Trigger> FinishTriggerParams = List.of();

    public record Trigger(String TriggerType, String TriggerParam) {}

    @Override
    public int getId() {
        return TutorialID;
    }
}

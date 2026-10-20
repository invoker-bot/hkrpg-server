"""原生回归场景：每个阶段都由 KCP 请求推进，在阶段之间重启实际 H2 进程。"""

LEGACY_IDS = (20001632, 20111656, 20211656, 20241651, 10301656, 10401651, 10403660)
DYNAMIC_IDS = (10301634, 20311667, 20242671, 20121671, 20121676,
               20413666, 20423666, 20412666, 20413667)
REMAINING_IDS = (20331662, 20121686, 20211675, 20222671, 20221671, 20433691,
                 20433697, 20123655, 20134651, 20003658, 20223651, 20241665)

# Story acceptances/location only. Completion, prop/floor/group state and loot are native actions.
REMAINING_PREREQUISITES = {
    20134651: dict(main=2011409),
    20003658: dict(main=2000303, completed=[200030301]),
    20223651: dict(completedMain=8015163),
    20211675: dict(main=4015103, doing=[401510301]),
    20222671: dict(main=4015202, doing=[401520202]),
    20221671: dict(main=4015301, doing=[401530101]),
    20121686: dict(main=2010503, doing=[201050301]),
    20433691: dict(main=4016201, doing=[401620103], floorZero="FSV_TimeLock_1"),
    20433697: dict(main=4016201, doing=[401620115], floorZero="FSV_TimeLock_7"),
}


def remaining_actions(chests, catalog):
    actions = [None]
    for chest in chests:
        id_ = chest["id"]
        rule = catalog["rules"][str(id_)]
        common = dict(chest, controller=rule["controller"],
                      actorGroup=rule.get("controllerGroup", chest["group"]),
                      finish=rule["finish"] or next(iter(rule["reports"]), 0),
                      defeated=[], remaining=[])
        if id_ == 20121686:
            common["unloadsAfterClaim"] = True  # G54 requires main 2010503 in Accept phase.
        initial = 20 if rule["initial"] == "Hidden" else 11
        if rule["kind"] == "GROUP_PROPERTY":
            common.update(property=rule["property"], value=1, floorValue=rule["floorValue"])
            goal = 401620103 if id_ == 20433691 else 401620115
            actions.append(dict(common, op="property", before=initial, after=12,
                                missions={str(goal): "DOING" if rule.get("lateFloorValue") else "FINISHED"}))
            if rule.get("lateFloorValue"):
                actions.append(dict(common, op="property", before=12, after=12,
                                    property="Srv_KindlingFall", floorValue=rule["lateFloorValue"],
                                    late=True, missions={str(goal): "FINISHED"}))
        elif id_ == 20121686:
            actions.append(dict(common, op="talk", talk=201050301, before=initial, after=12))
        else:
            # Exact InteractConfig targets: 1010 -> Open, 11090 -> TriggerDisable.
            states = {str(rule["controller"]): {1010: 1, 11090: 9}[rule["finish"]]} if rule["controller"] else {}
            before = {str(rule["controller"]): 10 if rule["kind"] == "JIGSAW" else 0} if rule["controller"] else {}
            if id_ == 20222671:
                common["absentAfter"] = [rule["controller"]]
            actions.append(dict(common, op="finish", before=initial,
                                after=initial if id_ == 20222671 else 12,
                                beforeStates=before, states={} if id_ == 20222671 else states,
                                persistedStates=states))
            if id_ == 20222671:
                actions.append(dict(common, op="talk", talk=401520203, before=initial, after=12,
                                    beforeStates={}, states={}))
        actions += [dict(common, op="claim", before=12, after=13),
                    dict(common, op="verify", before=13, after=13)]
        if rule.get("mission"):
            actions[-2]["missions"] = {str(rule["mission"]): "FINISHED"}
        for action in actions:
            if not action or action.get("id") != id_:
                continue
            if action["op"] == "talk":
                action.setdefault("missions", {})[str(action["talk"])] = "FINISHED"
            if action["op"] == "finish" and rule.get("controllerMission"):
                action.setdefault("missions", {})[str(rule["controllerMission"])] = "FINISHED"
    return actions


def dynamic_actions(chests, catalog):
    actions = [None]
    for chest in chests:
        id_ = chest["id"]
        rule = catalog["rules"][str(id_)]
        controller = catalog["controllerAliases"].get(str(id_), [rule["controller"]])[0]
        common = dict(chest, controller=controller, finish=rule["finish"], defeated=[], remaining=[])
        if rule["kind"] == "SPEAR":
            common["shield"] = rule["shield"]
        initial = 20 if rule["initial"] == "Hidden" else 11
        if id_ == 10301634:
            actions += [
                dict(common, op="step", configs=[1210], before=20, after=20,
                     beforeStates={"300001": 2, "300002": 9, "300003": 9},
                     states={"300001": 1, "300002": 10, "300003": 10}),
                dict(common, op="step", configs=[11000], before=20, after=20,
                     beforeStates={"300001": 1, "300002": 10, "300003": 10},
                     states={"300001": 1, "300002": 0, "300003": 10}, target=300002),
                dict(common, op="step", configs=[11000], before=20, after=20,
                     beforeStates={"300001": 1, "300002": 0, "300003": 10},
                     states={"300001": 10, "300002": 0, "300003": 0}, target=300003)]
        if rule["kind"] == "SPEAR":
            actions += [
                dict(common, op="step", configs=rule["interactions"][:1], before=initial, after=initial,
                     beforeStates={str(controller): 0}, states={str(controller): 1}),
                dict(common, op="step", configs=rule["interactions"][1:], before=initial, after=initial,
                     beforeStates={str(controller): 1}, states={str(controller): 2}),
                dict(common, op="destroy", before=initial, after=12,
                     beforeStates={str(controller): 2}, states={str(controller): 2})]
        else:
            end = 0 if rule["kind"] == "JIGSAW" or id_ in (20121671, 20121676) else 1
            begin = 10 if rule["kind"] == "JIGSAW" else 1 if end == 0 else 0
            actions.append(dict(common, op="finish", before=initial, after=12,
                                beforeStates={str(controller): begin}, states={str(controller): end}))
        actions += [dict(common, op="claim", before=12, after=13),
                    dict(common, op="verify", before=13, after=13)]
    return actions

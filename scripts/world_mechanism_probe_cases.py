"""原生回归场景：每个阶段都由 KCP 请求推进，在阶段之间重启实际 H2 进程。"""

LEGACY_IDS = (20001632, 20111656, 20211656, 20241651, 10301656, 10401651, 10403660)
DYNAMIC_IDS = (10301634, 20311667, 20242671, 20121671, 20121676,
               20413666, 20423666, 20412666, 20413667)


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

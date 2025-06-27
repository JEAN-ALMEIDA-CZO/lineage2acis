### Eclipse Workspace Patch 1.0
#P L2jMega_Interlude
diff --git java/Base/AutoFarm/AutofarmConstants.java java/Base/AutoFarm/AutofarmConstants.java
new file mode 100644
index 0000000..70e4aef
--- /dev/null
+++ java/Base/AutoFarm/AutofarmConstants.java
@@ -0,0 +1,11 @@
+package Base.AutoFarm;
+
+import java.util.Arrays;
+import java.util.List;
+
+public class AutofarmConstants 
+{
+    public final static List<Integer> attackSlots = Arrays.asList(0, 1, 2, 3);
+    public final static List<Integer> chanceSlots = Arrays.asList(4, 5, 6, 7);
+    public final static List<Integer> lowLifeSlots = Arrays.asList(8, 9, 10, 11);
+}
\ No newline at end of file
diff --git java/Base/AutoFarm/AutofarmPlayerRoutine.java java/Base/AutoFarm/AutofarmPlayerRoutine.java
new file mode 100644
index 0000000..68597fc
--- /dev/null
+++ java/Base/AutoFarm/AutofarmPlayerRoutine.java
@@ -0,0 +1,505 @@
+package Base.AutoFarm;
+
+
+
+import com.l2jmega.Config;
+import com.l2jmega.gameserver.geoengine.GeoEngine;
+import com.l2jmega.gameserver.handler.IItemHandler;
+import com.l2jmega.gameserver.handler.ItemHandler;
+import com.l2jmega.gameserver.handler.voicedcommandhandlers.VoicedAutoFarm;
+import com.l2jmega.gameserver.model.L2ShortCut;
+import com.l2jmega.gameserver.model.L2Skill;
+import com.l2jmega.gameserver.model.WorldObject;
+import com.l2jmega.gameserver.model.WorldRegion;
+import com.l2jmega.gameserver.model.actor.Creature;
+import com.l2jmega.gameserver.model.actor.Summon;
+import com.l2jmega.gameserver.model.actor.ai.CtrlEvent;
+import com.l2jmega.gameserver.model.actor.ai.CtrlIntention;
+import com.l2jmega.gameserver.model.actor.ai.NextAction;
+import com.l2jmega.gameserver.model.actor.instance.Chest;
+import com.l2jmega.gameserver.model.actor.instance.Monster;
+import com.l2jmega.gameserver.model.actor.instance.Pet;
+import com.l2jmega.gameserver.model.actor.instance.Player;
+import com.l2jmega.gameserver.model.item.instance.ItemInstance;
+import com.l2jmega.gameserver.network.SystemMessageId;
+import com.l2jmega.gameserver.network.serverpackets.ActionFailed;
+import com.l2jmega.gameserver.network.serverpackets.ExShowScreenMessage;
+import com.l2jmega.gameserver.network.serverpackets.ExShowScreenMessage.SMPOS;
+import com.l2jmega.gameserver.network.serverpackets.SystemMessage;
+import com.l2jmega.gameserver.templates.skills.L2SkillType;
+
+import java.util.ArrayList;
+import java.util.Arrays;
+import java.util.Collections;
+import java.util.List;
+import java.util.concurrent.ScheduledFuture;
+import java.util.function.Function;
+import java.util.stream.Collectors;
+
+import com.l2jmega.commons.concurrent.ThreadPool;
+import com.l2jmega.commons.math.MathUtil;
+import com.l2jmega.commons.random.Rnd;
+
+public class AutofarmPlayerRoutine
+{
+	private final Player player;
+	private ScheduledFuture<?> _task;
+	private Creature committedTarget = null;
+
+	public AutofarmPlayerRoutine(Player player)
+	{
+		this.player = player;
+	}
+
+	public void start()
+	{
+		if (_task == null)
+		{
+			_task = ThreadPool.scheduleAtFixedRate(() -> executeRoutine(), 450, 450);
+			
+			player.sendPacket(new ExShowScreenMessage("Auto Farming Actived...", 5*1000, SMPOS.TOP_CENTER, false));
+			player.sendPacket(new SystemMessage(SystemMessageId.AUTO_FARM_ACTIVATED));
+		    
+		
+		}
+	}
+	
+	public void stop()
+	{
+		if (_task != null)
+		{
+			_task.cancel(false);
+			_task = null;
+			
+			player.sendPacket(new ExShowScreenMessage("Auto Farming Deactivated...", 5*1000, SMPOS.TOP_CENTER, false));
+			player.sendPacket(new SystemMessage(SystemMessageId.AUTO_FARM_DESACTIVATED));
+		   
+		}
+	}
+	
+	public void executeRoutine()
+	{
+		if (player.isNoBuffProtected() && player.getAllEffects().length <= 8)
+		{
+			player.sendMessage("You don't have buffs to use autofarm.");
+			player.broadcastUserInfo();
+			stop();	
+			player.setAutoFarm(false);
+			VoicedAutoFarm.showAutoFarm(player);
+			return;
+		}
+		
+		calculatePotions();
+		checkSpoil();
+		targetEligibleCreature();
+	    if (player.isMageClass()) 
+	    {
+	        useAppropriateSpell(); 
+	    }
+	    else if (shotcutsContainAttack())
+	    {
+	        attack(); 
+	    }
+	    else
+	    {
+	        useAppropriateSpell(); 
+	    }
+		checkSpoil();
+		useAppropriateSpell();
+	}
+
+	private void attack() 
+	{
+		Boolean shortcutsContainAttack = shotcutsContainAttack();
+		
+	    if (shortcutsContainAttack) 
+	        physicalAttack();
+	}
+
+	private void useAppropriateSpell() 
+	{
+		L2Skill chanceSkill = nextAvailableSkill(getChanceSpells(), AutofarmSpellType.Chance);
+
+		if (chanceSkill != null)
+		{
+			useMagicSkill(chanceSkill, false);
+			return;
+		}
+
+		L2Skill lowLifeSkill = nextAvailableSkill(getLowLifeSpells(), AutofarmSpellType.LowLife);
+
+		if (lowLifeSkill != null) 
+		{
+			useMagicSkill(lowLifeSkill, true);
+			return;
+		}
+
+		L2Skill attackSkill = nextAvailableSkill(getAttackSpells(), AutofarmSpellType.Attack);
+
+		if (attackSkill != null) 
+		{
+			useMagicSkill(attackSkill, false);
+			return;
+		}
+	}
+
+	public L2Skill nextAvailableSkill(List<Integer> skillIds, AutofarmSpellType spellType) 
+	{
+		for (Integer skillId : skillIds) 
+		{
+			L2Skill skill = player.getSkill(skillId);
+
+			if (skill == null) 
+				continue;
+			
+			if (skill.getSkillType() == L2SkillType.SIGNET || skill.getSkillType() == L2SkillType.SIGNET_CASTTIME)
+				continue;
+
+			if (!player.checkDoCastConditions(skill)) 
+				continue;
+
+			if (isSpoil(skillId))
+			{
+				if (monsterIsAlreadySpoiled())
+				{
+					continue;
+				}
+				return skill;
+			}
+			
+			if (spellType == AutofarmSpellType.Chance && getMonsterTarget() != null)
+			{
+				if (getMonsterTarget().getFirstEffect(skillId) == null) 
+					return skill;
+				continue;
+			}
+
+			if (spellType == AutofarmSpellType.LowLife && getHpPercentage() > player.getHealPercent()) 
+				break;
+
+			return skill;
+		}
+
+		return null;
+	}
+	
+	private void checkSpoil() 
+	{
+		if (canBeSweepedByMe() && getMonsterTarget().isDead()) 
+		{
+			L2Skill sweeper = player.getSkill(42);
+			if (sweeper == null) 
+				return;
+			
+			useMagicSkill(sweeper, false);
+		}
+	}
+
+	private Double getHpPercentage() 
+	{
+		return player.getCurrentHp() * 100.0f / player.getMaxHp();
+	}
+
+	private Double percentageMpIsLessThan() 
+	{
+		return player.getCurrentMp() * 100.0f / player.getMaxMp();
+	}
+
+	private Double percentageHpIsLessThan()
+	{
+		return player.getCurrentHp() * 100.0f / player.getMaxHp();
+	}
+
+	private List<Integer> getAttackSpells()
+	{
+		return getSpellsInSlots(AutofarmConstants.attackSlots);
+	}
+
+	private List<Integer> getSpellsInSlots(List<Integer> attackSlots) 
+	{
+		return Arrays.stream(player.getAllShortCuts()).filter(shortcut -> shortcut.getPage() == player.getPage() && shortcut.getType() == L2ShortCut.TYPE_SKILL && attackSlots.contains(shortcut.getSlot())).map(L2ShortCut::getId).collect(Collectors.toList());
+	}
+
+	private List<Integer> getChanceSpells()
+	{
+		return getSpellsInSlots(AutofarmConstants.chanceSlots);
+	}
+
+	private List<Integer> getLowLifeSpells()
+	{
+		return getSpellsInSlots(AutofarmConstants.lowLifeSlots);
+	}
+
+	private boolean shotcutsContainAttack() 
+	{
+		return Arrays.stream(player.getAllShortCuts()).anyMatch(shortcut -> shortcut.getPage() == player.getPage() && shortcut.getType() == L2ShortCut.TYPE_ACTION && (shortcut.getId() == 2 || player.isSummonAttack() && shortcut.getId() == 22));
+	}
+	
+	private boolean monsterIsAlreadySpoiled()
+	{
+		return getMonsterTarget() != null && getMonsterTarget().getSpoilerId() != 0;
+	}
+	
+	private static boolean isSpoil(Integer skillId)
+	{
+		return skillId == 254 || skillId == 302;
+	}
+	
+	private boolean canBeSweepedByMe() 
+	{
+	    return getMonsterTarget() != null && getMonsterTarget().isDead() && getMonsterTarget().getSpoilerId() == player.getObjectId();
+	}
+	
+	private void castSpellWithAppropriateTarget(L2Skill skill, Boolean forceOnSelf)
+	{
+		if (forceOnSelf) 
+		{
+			WorldObject oldTarget = player.getTarget();
+			player.setTarget(player);
+			player.useMagic(skill, false, false);
+			player.setTarget(oldTarget);
+			return;
+		}
+
+		player.useMagic(skill, false, false);
+	}
+
+	private void physicalAttack()
+	{
+	    if (!(player.getTarget() instanceof Monster)) 
+	        return;
+
+	    Monster target = (Monster) player.getTarget();
+
+	    if (!player.isMageClass()) 
+	    {
+	        if (target.isAutoAttackable(player) && GeoEngine.getInstance().canSeeTarget(player, target))
+	        {
+	            if (GeoEngine.getInstance().canSeeTarget(player, target))
+	            {
+	                player.getAI().setIntention(CtrlIntention.ATTACK, target);
+	                player.onActionRequest();
+
+	                if (player.isSummonAttack() && player.getPet() != null)
+	                {
+	                    // Siege Golem's
+	                    if (player.getPet().getNpcId() >= 14702 && player.getPet().getNpcId() <= 14798 || player.getPet().getNpcId() >= 14839 && player.getPet().getNpcId() <= 14869)
+	                        return;
+
+	                    Summon activeSummon = player.getPet();
+	                    activeSummon.setTarget(target);
+	                    activeSummon.getAI().setIntention(CtrlIntention.ATTACK, target);
+
+	                    int[] summonAttackSkills = {4261, 4068, 4137, 4260, 4708, 4709, 4710, 4712, 5135, 5138, 5141, 5442, 5444, 6095, 6096, 6041, 6044};
+	                    if (Rnd.get(100) < player.getSummonSkillPercent())
+	                    {
+	                        for (int skillId : summonAttackSkills)
+	                        {
+	                            useMagicSkillBySummon(skillId, target);
+	                        }
+	                    }
+	                }
+	            }
+	        }
+	        else
+	        {
+	            if (target.isAutoAttackable(player) && GeoEngine.getInstance().canSeeTarget(player, target))
+	            if (GeoEngine.getInstance().canSeeTarget(player, target))
+	                player.getAI().setIntention(CtrlIntention.FOLLOW, target);
+	        }
+	    }
+	    else
+	    {
+	        if (player.isSummonAttack() && player.getPet() != null)
+	        {
+	            // Siege Golem's
+	            if (player.getPet().getNpcId() >= 14702 && player.getPet().getNpcId() <= 14798 || player.getPet().getNpcId() >= 14839 && player.getPet().getNpcId() <= 14869)
+	                return;
+
+	            Summon activeSummon = player.getPet();
+	            activeSummon.setTarget(target);
+	            activeSummon.getAI().setIntention(CtrlIntention.ATTACK, target);
+
+	            int[] summonAttackSkills = {4261, 4068, 4137, 4260, 4708, 4709, 4710, 4712, 5135, 5138, 5141, 5442, 5444, 6095, 6096, 6041, 6044};
+	            if (Rnd.get(100) < player.getSummonSkillPercent())
+	            {
+	                for (int skillId : summonAttackSkills)
+	                {
+	                    useMagicSkillBySummon(skillId, target);
+	                }
+	            }
+	        }
+	    }
+	}
+
+
+	public void targetEligibleCreature() {
+	    if (player.getTarget() == null) {
+	        selectNewTarget(); 
+	        return;
+	    }
+
+	    if (committedTarget != null) {
+	      if (!committedTarget.isDead() && GeoEngine.getInstance().canSeeTarget(player, committedTarget)) {
+	            attack();
+	            return;
+	        } else if (!GeoEngine.getInstance().canSeeTarget(player, committedTarget)) {
+	            committedTarget = null;
+	            selectNewTarget(); 
+	            return;
+	        }
+	        player.getAI().setIntention(CtrlIntention.FOLLOW, committedTarget);
+	        committedTarget = null;
+	        player.setTarget(null);
+	    }
+	    
+	    if (committedTarget instanceof Summon) 
+	        return;
+	        
+	    List<Monster> targets = getKnownMonstersInRadius(player, player.getRadius(), creature -> GeoEngine.getInstance().canMoveToTarget(player.getX(), player.getY(), player.getZ(), creature.getX(), creature.getY(), creature.getZ()) && !player.ignoredMonsterContain(creature.getNpcId()) && !creature.isRaidMinion() && !creature.isRaid() && !creature.isDead() && !(creature instanceof Chest) && !(player.isAntiKsProtected() && creature.getTarget() != null && creature.getTarget() != player && creature.getTarget() != player.getPet()) );
+
+	    if (targets.isEmpty())
+	        return;
+
+	    Monster closestTarget = targets.stream().min((o1, o2) -> Integer.compare((int) Math.sqrt(player.getDistanceSq(o1)), (int) Math.sqrt(player.getDistanceSq(o2)))).get();
+
+	    committedTarget = closestTarget;
+	    player.setTarget(closestTarget);
+	}
+
+
+	
+
+
+	
+	private void selectNewTarget() 
+	{
+	    List<Monster> targets = getKnownMonstersInRadius(player, player.getRadius(), creature -> GeoEngine.getInstance().canMoveToTarget(player.getX(), player.getY(), player.getZ(), creature.getX(), creature.getY(), creature.getZ()) && !player.ignoredMonsterContain(creature.getNpcId()) && !creature.isRaidMinion() && !creature.isRaid() && !creature.isDead() && !(creature instanceof Chest) && !(player.isAntiKsProtected() && creature.getTarget() != null && creature.getTarget() != player && creature.getTarget() != player.getPet()) );
+
+	    if (targets.isEmpty())
+	        return;
+
+	    Monster closestTarget = targets.stream().min((o1, o2) -> Integer.compare((int) Math.sqrt(player.getDistanceSq(o1)), (int) Math.sqrt(player.getDistanceSq(o2)))).get();
+
+	    committedTarget = closestTarget;
+	    player.setTarget(closestTarget);
+	}
+
+
+
+
+	public final static List<Monster> getKnownMonstersInRadius(Player player, int radius, Function<Monster, Boolean> condition)
+	{
+		final WorldRegion region = player.getRegion();
+		if (region == null)
+			return Collections.emptyList();
+
+		final List<Monster> result = new ArrayList<>();
+
+		for (WorldRegion reg : region.getSurroundingRegions())
+		{
+			for (WorldObject obj : reg.getObjects())
+			{
+				if (!(obj instanceof Monster) || !MathUtil.checkIfInRange(radius, player, obj, true) || !condition.apply((Monster) obj))
+					continue;
+
+				result.add((Monster) obj);
+			}
+		}
+
+		return result;
+	}
+
+	public Monster getMonsterTarget()
+	{
+		if(!(player.getTarget() instanceof Monster)) 
+		{
+			return null;
+		}
+
+		return (Monster)player.getTarget();
+	}
+
+	private void useMagicSkill(L2Skill skill, Boolean forceOnSelf)
+	{
+		if (skill.getSkillType() == L2SkillType.RECALL && !Config.KARMA_PLAYER_CAN_TELEPORT && player.getKarma() > 0)
+		{
+			player.sendPacket(ActionFailed.STATIC_PACKET);
+			return;
+		}
+
+		if (skill.isToggle() && player.isMounted())
+		{
+			player.sendPacket(ActionFailed.STATIC_PACKET);
+			return;
+		}
+
+		if (player.isOutOfControl())
+		{
+			player.sendPacket(ActionFailed.STATIC_PACKET);
+			return;
+		}
+
+		if (player.isAttackingNow())
+			player.getAI().setNextAction(new NextAction(CtrlEvent.EVT_READY_TO_ACT, CtrlIntention.CAST, () -> castSpellWithAppropriateTarget(skill, forceOnSelf)));
+		else 
+			castSpellWithAppropriateTarget(skill, forceOnSelf);
+	}
+	
+	private boolean useMagicSkillBySummon(int skillId, WorldObject target)
+	{
+		// No owner, or owner in shop mode.
+		if (player == null || player.isInStoreMode())
+			return false;
+		
+		final Summon activeSummon = player.getPet();
+		if (activeSummon == null)
+			return false;
+		
+		// Pet which is 20 levels higher than owner.
+		if (activeSummon instanceof Pet && activeSummon.getLevel() - player.getLevel() > 20)
+		{
+			player.sendPacket(SystemMessageId.PET_TOO_HIGH_TO_CONTROL);
+			return false;
+		}
+		
+		// Out of control pet.
+		if (activeSummon.isOutOfControl())
+		{
+			player.sendPacket(SystemMessageId.PET_REFUSING_ORDER);
+			return false;
+		}
+		
+		// Verify if the launched skill is mastered by the summon.
+		final L2Skill skill = activeSummon.getSkill(skillId);
+		if (skill == null)
+			return false;
+		
+		// Can't launch offensive skills on owner.
+		if (skill.isOffensive() && player == target)
+			return false;
+		
+		activeSummon.setTarget(target);
+		return activeSummon.useMagic(skill, false, false);
+	}
+
+	private void calculatePotions()
+	{
+		if (percentageHpIsLessThan() < player.getHpPotionPercentage())
+			forceUseItem(1539); 
+
+		if (percentageMpIsLessThan() < player.getMpPotionPercentage())
+			forceUseItem(728); 
+	}	
+
+	private void forceUseItem(int itemId)
+	{
+		final ItemInstance potion = player.getInventory().getItemByItemId(itemId);
+		if (potion == null)
+			return; 
+
+		final IItemHandler handler = ItemHandler.getInstance().getItemHandler(potion.getEtcItem());
+		if (handler != null)
+			handler.useItem(player, potion, false); 
+	}	
+}
\ No newline at end of file
diff --git java/Base/AutoFarm/AutofarmSpell.java java/Base/AutoFarm/AutofarmSpell.java
new file mode 100644
index 0000000..beefc8d
--- /dev/null
+++ java/Base/AutoFarm/AutofarmSpell.java
@@ -0,0 +1,20 @@
+package Base.AutoFarm;
+
+public class AutofarmSpell {
+	private final Integer _skillId;
+	private final AutofarmSpellType _spellType;
+	
+	public AutofarmSpell(Integer skillId, AutofarmSpellType spellType){
+		
+		_skillId = skillId;
+		_spellType = spellType;
+	}
+	
+	public Integer getSkillId() {
+		return _skillId;
+	}
+	
+	public AutofarmSpellType getSpellType() {
+		return _spellType;
+	}
+}
\ No newline at end of file
diff --git java/Base/AutoFarm/AutofarmSpellType.java java/Base/AutoFarm/AutofarmSpellType.java
new file mode 100644
index 0000000..2ec3039
--- /dev/null
+++ java/Base/AutoFarm/AutofarmSpellType.java
@@ -0,0 +1,8 @@
+package Base.AutoFarm;
+
+public enum AutofarmSpellType
+{
+    Attack,
+    Chance,
+    LowLife    
+}
\ No newline at end of file
diff --git java/com/l2jmega/gameserver/handler/VoicedCommandHandler.java java/com/l2jmega/gameserver/handler/VoicedCommandHandler.java
index 7532a04..8463819 100644
--- java/com/l2jmega/gameserver/handler/VoicedCommandHandler.java
+++ java/com/l2jmega/gameserver/handler/VoicedCommandHandler.java
@@ -17,6 +17,7 @@
 import java.util.HashMap;
 import java.util.Map;
 
+import com.l2jmega.gameserver.handler.voicedcommandhandlers.VoicedAutoFarm;
 import com.l2jmega.gameserver.handler.voicedcommandhandlers.VoicedAutoPotion;
 import com.l2jmega.gameserver.handler.voicedcommandhandlers.VoicedBanking;
 import com.l2jmega.gameserver.handler.voicedcommandhandlers.VoicedBossSpawn;
@@ -52,6 +53,7 @@
 		registerHandler(new VoicedAutoPotion());
 		registerHandler(new VoicedPassword());
 		registerHandler(new VoicedEnchant());
+		registerHandler(new VoicedAutoFarm());
 		
 		registerHandler(new VoicedTrySkin());
 		registerHandler(new VoicedRanking());
diff --git java/com/l2jmega/gameserver/handler/voicedcommandhandlers/VoicedAutoFarm.java java/com/l2jmega/gameserver/handler/voicedcommandhandlers/VoicedAutoFarm.java
new file mode 100644
index 0000000..a2c1c4a
--- /dev/null
+++ java/com/l2jmega/gameserver/handler/voicedcommandhandlers/VoicedAutoFarm.java
@@ -0,0 +1,325 @@
+package com.l2jmega.gameserver.handler.voicedcommandhandlers;
+
+import com.l2jmega.gameserver.handler.IVoicedCommandHandler;
+import com.l2jmega.gameserver.model.WorldObject;
+import com.l2jmega.gameserver.model.actor.instance.Monster;
+import com.l2jmega.gameserver.model.actor.instance.Player;
+import com.l2jmega.gameserver.network.SystemMessageId;
+import com.l2jmega.gameserver.network.serverpackets.ExShowScreenMessage;
+import com.l2jmega.gameserver.network.serverpackets.ExShowScreenMessage.SMPOS;
+import com.l2jmega.gameserver.network.serverpackets.NpcHtmlMessage;
+import com.l2jmega.gameserver.network.serverpackets.SystemMessage;
+
+import java.util.StringTokenizer;
+
+import com.l2jmega.commons.lang.StringUtil;
+
+import Base.AutoFarm.AutofarmPlayerRoutine;
+
+
+
+
+
+public class VoicedAutoFarm implements IVoicedCommandHandler 
+{
+    private final String[] VOICED_COMMANDS = 
+    {
+    	"autofarm",
+    	"enableAutoFarm",
+    	"radiusAutoFarm",
+    	"pageAutoFarm",
+    	"enableBuffProtect",
+    	"healAutoFarm",
+    	"hpAutoFarm",
+    	"mpAutoFarm",
+    	"enableAntiKs",
+    	"enableSummonAttack",
+    	"summonSkillAutoFarm",
+    	"ignoreMonster",
+    	"activeMonster"
+   };
+
+    @Override
+    public boolean useVoicedCommand(final String command, final Player activeChar, final String args)
+   {
+		final AutofarmPlayerRoutine bot = activeChar.getBot();
+		
+    	if (command.startsWith("autofarm"))
+    		showAutoFarm(activeChar);
+   	
+		if (command.startsWith("radiusAutoFarm"))
+		{
+			StringTokenizer st = new StringTokenizer(command, " ");
+			st.nextToken();
+			try
+			{
+				String param = st.nextToken();
+
+				if (param.startsWith("inc_radius"))
+				{
+					activeChar.setRadius(activeChar.getRadius() + 200);
+					showAutoFarm(activeChar);
+				}
+				else if (param.startsWith("dec_radius"))
+				{
+					activeChar.setRadius(activeChar.getRadius() - 200);
+					showAutoFarm(activeChar);
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			catch (Exception e)
+			{
+				e.printStackTrace();
+			}
+		}
+		
+		if (command.startsWith("pageAutoFarm"))
+		{
+			StringTokenizer st = new StringTokenizer(command, " ");
+			st.nextToken();
+			try
+			{
+				String param = st.nextToken();
+
+				if (param.startsWith("inc_page"))
+				{
+					activeChar.setPage(activeChar.getPage() + 1);
+					showAutoFarm(activeChar);
+				}
+				else if (param.startsWith("dec_page"))
+				{
+					activeChar.setPage(activeChar.getPage() - 1);
+					showAutoFarm(activeChar);
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			catch (Exception e)
+			{
+				e.printStackTrace();
+			}
+		}
+		
+		if (command.startsWith("healAutoFarm"))
+		{
+			StringTokenizer st = new StringTokenizer(command, " ");
+			st.nextToken();
+			try
+			{
+				String param = st.nextToken();
+
+				if (param.startsWith("inc_heal"))
+				{
+					activeChar.setHealPercent(activeChar.getHealPercent() + 10);
+					showAutoFarm(activeChar);
+				}
+				else if (param.startsWith("dec_heal"))
+				{
+					activeChar.setHealPercent(activeChar.getHealPercent() - 10);
+					showAutoFarm(activeChar);
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			catch (Exception e)
+			{
+				e.printStackTrace();
+			}
+		}
+		
+		if (command.startsWith("hpAutoFarm"))
+		{
+			StringTokenizer st = new StringTokenizer(command, " ");
+			st.nextToken();
+			try
+			{
+				String param = st.nextToken();
+
+				if (param.contains("inc_hp_pot"))
+				{
+					activeChar.setHpPotionPercentage(activeChar.getHpPotionPercentage() + 5);
+					showAutoFarm(activeChar);
+				}
+				else if (param.contains("dec_hp_pot"))
+				{
+					activeChar.setHpPotionPercentage(activeChar.getHpPotionPercentage() - 5);
+					showAutoFarm(activeChar);
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			catch (Exception e)
+			{
+				e.printStackTrace();
+			}
+		}
+		
+		if (command.startsWith("mpAutoFarm"))
+		{
+			StringTokenizer st = new StringTokenizer(command, " ");
+			st.nextToken();
+			try
+			{
+				String param = st.nextToken();
+
+				if (param.contains("inc_mp_pot"))
+				{
+					activeChar.setMpPotionPercentage(activeChar.getMpPotionPercentage() + 5);
+					showAutoFarm(activeChar);
+				}
+				else if (param.contains("dec_mp_pot"))
+				{
+					activeChar.setMpPotionPercentage(activeChar.getMpPotionPercentage() - 5);
+					showAutoFarm(activeChar);
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			catch (Exception e)
+			{
+				e.printStackTrace();
+			}
+		}
+		
+		if (command.startsWith("enableAutoFarm"))
+		{
+	    	if (activeChar.isAutoFarm())
+	    	{
+	    		bot.stop();
+				activeChar.setAutoFarm(false);
+	    	}
+	    	else
+	    	{
+	    		bot.start();
+				activeChar.setAutoFarm(true);
+	    	}
+
+	    	showAutoFarm(activeChar);
+		}
+		
+		if (command.startsWith("enableBuffProtect"))
+		{
+			activeChar.setNoBuffProtection(!activeChar.isNoBuffProtected());
+			showAutoFarm(activeChar);
+			activeChar.saveAutoFarmSettings();
+		}
+		
+		if (command.startsWith("enableAntiKs"))
+		{
+			activeChar.setAntiKsProtection(!activeChar.isAntiKsProtected());
+			
+			if(activeChar.isAntiKsProtected()) {
+				activeChar.sendPacket(new SystemMessage(SystemMessageId.ACTIVATE_RESPECT_HUNT));
+				activeChar.sendPacket(new ExShowScreenMessage("Respct Hunt On" , 3*1000, SMPOS.TOP_CENTER, false));
+			}else {
+				activeChar.sendPacket(new SystemMessage(SystemMessageId.DESACTIVATE_RESPECT_HUNT));
+				activeChar.sendPacket(new ExShowScreenMessage("Respct Hunt Off" , 3*1000, SMPOS.TOP_CENTER, false));
+			}
+			
+			activeChar.saveAutoFarmSettings();
+			showAutoFarm(activeChar);
+		}
+		
+		if (command.startsWith("enableSummonAttack"))
+		{
+			activeChar.setSummonAttack(!activeChar.isSummonAttack());
+			if(activeChar.isSummonAttack()) {
+				activeChar.sendPacket(new SystemMessage(SystemMessageId.ACTIVATE_SUMMON_ACTACK));
+				activeChar.sendPacket(new ExShowScreenMessage("Auto Farm Summon Attack On" , 3*1000, SMPOS.TOP_CENTER, false));
+			}else {
+				activeChar.sendPacket(new SystemMessage(SystemMessageId.DESACTIVATE_SUMMON_ACTACK));
+				activeChar.sendPacket(new ExShowScreenMessage("Auto Farm Summon Attack Off" , 3*1000, SMPOS.TOP_CENTER, false));
+			}
+			activeChar.saveAutoFarmSettings();
+			showAutoFarm(activeChar);
+		}
+		
+		if (command.startsWith("summonSkillAutoFarm"))
+		{
+			StringTokenizer st = new StringTokenizer(command, " ");
+			st.nextToken();
+			try
+			{
+				String param = st.nextToken();
+
+				if (param.startsWith("inc_summonSkill"))
+				{
+					activeChar.setSummonSkillPercent(activeChar.getSummonSkillPercent() + 10);
+					showAutoFarm(activeChar);
+				}
+				else if (param.startsWith("dec_summonSkill"))
+				{
+					activeChar.setSummonSkillPercent(activeChar.getSummonSkillPercent() - 10);
+					showAutoFarm(activeChar);
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			catch (Exception e)
+			{
+				e.printStackTrace();
+			}
+		}
+		
+		if (command.startsWith("ignoreMonster"))
+		{
+			int monsterId = 0;
+			WorldObject target = activeChar.getTarget();
+			if (target instanceof Monster)
+				monsterId = ((Monster) target).getNpcId();
+			
+			if (target == null)
+			{
+				activeChar.sendMessage("You dont have a target");
+				return false;
+			}
+			
+			activeChar.sendMessage(target.getName() + " has been added to the ignore list.");
+			activeChar.ignoredMonster(monsterId);
+		}
+		
+		if (command.startsWith("activeMonster"))
+		{
+			int monsterId = 0;
+			WorldObject target = activeChar.getTarget();
+			if (target instanceof Monster)
+				monsterId = ((Monster) target).getNpcId();
+			
+			if (target == null)
+			{
+				activeChar.sendMessage("You dont have a target");
+				return false;
+			}
+			
+			activeChar.sendMessage(target.getName() + " has been removed from the ignore list.");
+			activeChar.activeMonster(monsterId);
+		}
+		
+        return false;
+    }
+   
+	private static final String ACTIVED = "<font color=00FF00>STARTED</font>";
+	private static final String DESATIVED = "<font color=FF0000>STOPPED</font>";
+	private static final String STOP = "STOP";
+	private static final String START = "START";
+	
+	public static void showAutoFarm(Player activeChar)
+	{
+		NpcHtmlMessage html = new NpcHtmlMessage(0);
+		html.setFile("data/html/mods/menu/AutoFarm.htm"); 
+		html.replace("%player%", activeChar.getName());
+		html.replace("%page%", StringUtil.formatNumber(activeChar.getPage() + 1));
+		html.replace("%heal%", StringUtil.formatNumber(activeChar.getHealPercent()));
+		html.replace("%radius%", StringUtil.formatNumber(activeChar.getRadius()));
+		html.replace("%summonSkill%", StringUtil.formatNumber(activeChar.getSummonSkillPercent()));
+		html.replace("%hpPotion%", StringUtil.formatNumber(activeChar.getHpPotionPercentage()));
+		html.replace("%mpPotion%", StringUtil.formatNumber(activeChar.getMpPotionPercentage()));
+		html.replace("%noBuff%", activeChar.isNoBuffProtected() ? "back=L2UI.CheckBox_checked fore=L2UI.CheckBox_checked" : "back=L2UI.CheckBox fore=L2UI.CheckBox");
+		html.replace("%summonAtk%", activeChar.isSummonAttack() ? "back=L2UI.CheckBox_checked fore=L2UI.CheckBox_checked" : "back=L2UI.CheckBox fore=L2UI.CheckBox");
+		html.replace("%antiKs%", activeChar.isAntiKsProtected() ? "back=L2UI.CheckBox_checked fore=L2UI.CheckBox_checked" : "back=L2UI.CheckBox fore=L2UI.CheckBox");
+		html.replace("%autofarm%", activeChar.isAutoFarm() ? ACTIVED : DESATIVED);
+		html.replace("%button%", activeChar.isAutoFarm() ? STOP : START);
+		activeChar.sendPacket(html);
+	}
+
+    @Override
+    public String[] getVoicedCommandList() 
+    {
+        return VOICED_COMMANDS;
+    }
+}
\ No newline at end of file
diff --git java/com/l2jmega/gameserver/model/actor/instance/Gatekeeper.java java/com/l2jmega/gameserver/model/actor/instance/Gatekeeper.java
index 8a667a8..ec52f89 100644
--- java/com/l2jmega/gameserver/model/actor/instance/Gatekeeper.java
+++ java/com/l2jmega/gameserver/model/actor/instance/Gatekeeper.java
@@ -7,6 +7,8 @@
 import com.l2jmega.commons.math.MathUtil;
 import com.l2jmega.commons.random.Rnd;
 
+import Base.AutoFarm.AutofarmPlayerRoutine;
+
 import com.l2jmega.Config;
 import com.l2jmega.events.ArenaTask;
 import com.l2jmega.gameserver.data.SpawnTable;
@@ -22,6 +24,7 @@
 import com.l2jmega.gameserver.network.SystemMessageId;
 import com.l2jmega.gameserver.network.serverpackets.ActionFailed;
 import com.l2jmega.gameserver.network.serverpackets.NpcHtmlMessage;
+import com.l2jmega.gameserver.network.serverpackets.SystemMessage;
 
 public final class Gatekeeper extends Folk
 {
@@ -48,6 +51,8 @@
 			player.sendMessage("You can not do that!");
 			return;
 		}
+		final AutofarmPlayerRoutine bot = player.getBot();
+		
 		
 		if (player.isArenaProtection())
 		{
@@ -1250,6 +1255,15 @@
 				
 				if (player.destroyItemByItemId("Teleport ", (list.isNoble()) ? 6651 : 57, price, this, true))
 					player.teleToLocation(list, 100);
+				player.sendPacket(new SystemMessage(SystemMessageId.WILL_BE_MOVED_INTERFACE));
+			
+			
+			if (player.isAutoFarm())
+			{
+				bot.stop();
+				player.setAutoFarm(false);
+			}
+				
 				
 				player.sendPacket(ActionFailed.STATIC_PACKET);
 			}
diff --git java/com/l2jmega/gameserver/model/actor/instance/Player.java java/com/l2jmega/gameserver/model/actor/instance/Player.java
index 845f9c1..2921ce9 100644
--- java/com/l2jmega/gameserver/model/actor/instance/Player.java
+++ java/com/l2jmega/gameserver/model/actor/instance/Player.java
@@ -269,6 +269,7 @@
 import com.l2jmega.commons.math.MathUtil;
 import com.l2jmega.commons.random.Rnd;
 
+import Base.AutoFarm.AutofarmPlayerRoutine;
 import phantom.Phantom_Archers;
 import phantom.Phantom_Attack;
 import phantom.Phantom_Farm;
@@ -10285,6 +10286,10 @@
 	@Override
 	public void deleteMe()
 	{
+		
+				
+				_bot.stop();
+				
 		cleanup();
 		store();
 		super.deleteMe();
@@ -10337,6 +10342,8 @@
 			// Remove the Player from the world
 			decayMe();
 			
+						_bot.stop();
+			
 			// If a party is in progress, leave it
 			if (isInParty())
 				leaveParty();
@@ -14469,4 +14476,220 @@
 	{
 		_dressMe = val;
 	}
+	
+	// ------------
+	// Autofarm
+	// ------------
+
+	private boolean _autoFarm;
+	
+	public void setAutoFarm(boolean comm)
+	{
+		_autoFarm = comm;
+	}
+
+	public boolean isAutoFarm()
+	{
+		return _autoFarm;
+	}
+	
+	private int autoFarmRadius = 1200;
+	
+	public void setRadius(int value) 
+	{
+		autoFarmRadius = MathUtil.limit(value, 200, 3000);
+	}
+	
+	public int getRadius()
+	{
+		return autoFarmRadius;
+	}
+	
+	private int autoFarmShortCut = 9;
+	
+	public void setPage(int value) 
+	{
+		autoFarmShortCut = MathUtil.limit(value, 0, 9);
+	}
+	
+	public int getPage()
+	{
+		return autoFarmShortCut;
+	}
+	
+	private int autoFarmHealPercente = 30;
+	
+	public void setHealPercent(int value) 
+	{
+		autoFarmHealPercente = MathUtil.limit(value, 20, 90);
+	}
+	
+	public int getHealPercent()
+	{
+		return autoFarmHealPercente;
+	}
+	
+	private boolean autoFarmBuffProtection = false;
+	
+	public void setNoBuffProtection(boolean val) 
+	{
+		autoFarmBuffProtection = val;
+	}
+	
+	public boolean isNoBuffProtected()
+	{
+		return autoFarmBuffProtection;
+	}
+	
+	private boolean autoAntiKsProtection = false;
+	
+	public void setAntiKsProtection(boolean val) 
+	{
+		autoAntiKsProtection = val;
+	}
+	
+	public boolean isAntiKsProtected()
+	{
+		return autoAntiKsProtection;
+	}
+	
+	private boolean autoFarmSummonAttack = false;
+	
+	public void setSummonAttack(boolean val) 
+	{
+		autoFarmSummonAttack = val;
+	}
+	
+	public boolean isSummonAttack()
+	{
+		return autoFarmSummonAttack;
+	}
+	
+	private int autoFarmSummonSkillPercente = 0;
+	
+	public void setSummonSkillPercent(int value) 
+	{
+		autoFarmSummonSkillPercente = MathUtil.limit(value, 0, 90);
+	}
+	
+	public int getSummonSkillPercent()
+	{
+		return autoFarmSummonSkillPercente;
+	}
+
+	private int hpPotionPercent = 60;
+	private int mpPotionPercent = 60;
+
+	public void setHpPotionPercentage(int value)
+	{
+		hpPotionPercent = MathUtil.limit(value, 0, 100);
+	}
+
+	public int getHpPotionPercentage() 
+	{
+		return hpPotionPercent;
+	}
+
+	public void setMpPotionPercentage(int value) 
+	{
+		mpPotionPercent = MathUtil.limit(value, 0, 100);
+	}
+
+	public int getMpPotionPercentage()
+	{
+		return mpPotionPercent;
+	}
+
+	private List<Integer> _ignoredMonster = new ArrayList<>();
+
+	public void ignoredMonster(Integer npcId)
+	{
+		_ignoredMonster.add(npcId);
+	}
+
+	public void activeMonster(Integer npcId)
+	{
+		if (_ignoredMonster.contains(npcId))
+			_ignoredMonster.remove(npcId);
+	}
+
+	public boolean ignoredMonsterContain(int npcId)
+	{
+		return _ignoredMonster.contains(npcId);
+	}
+
+	private AutofarmPlayerRoutine _bot = new AutofarmPlayerRoutine(this);
+	
+	public AutofarmPlayerRoutine getBot()
+	{
+		if (_bot == null)
+			_bot = new AutofarmPlayerRoutine(this);
+		
+		return _bot;
+	}
+	
+	
+    
+    public void saveAutoFarmSettings() {
+        try (Connection con = L2DatabaseFactory.getInstance().getConnection()) {
+            String updateSql = "REPLACE INTO character_autofarm (char_id, char_name,  radius, short_cut, heal_percent, buff_protection, anti_ks_protection, summon_attack, summon_skill_percent, hp_potion_percent, mp_potion_percent) VALUES (?, ?, ?,  ?, ?, ?, ?, ?, ?, ?, ?)";
+            try (PreparedStatement updateStatement = con.prepareStatement(updateSql)) {
+                updateStatement.setInt(1, getObjectId()); // char_id
+                updateStatement.setString(2, getName()); // char_name
+               
+                updateStatement.setInt(3, autoFarmRadius);
+                updateStatement.setInt(4, autoFarmShortCut);
+                updateStatement.setInt(5, autoFarmHealPercente);
+                updateStatement.setBoolean(6, autoFarmBuffProtection);
+                updateStatement.setBoolean(7, autoAntiKsProtection);
+                updateStatement.setBoolean(8, autoFarmSummonAttack);
+                updateStatement.setInt(9, autoFarmSummonSkillPercente);
+                updateStatement.setInt(10, hpPotionPercent);
+                updateStatement.setInt(11, mpPotionPercent);
+                updateStatement.executeUpdate();
+            }
+        } catch (SQLException e) {
+            e.printStackTrace();
+        }
+    }
+
+    public void loadAutoFarmSettings() {
+        try (Connection con = L2DatabaseFactory.getInstance().getConnection()) {
+            String selectSql = "SELECT * FROM character_autofarm WHERE char_id = ?";
+            try (PreparedStatement selectStatement = con.prepareStatement(selectSql)) {
+                selectStatement.setInt(1, getObjectId()); // char_id
+                try (ResultSet resultSet = selectStatement.executeQuery()) {
+                    if (resultSet.next()) {
+                       
+                        autoFarmRadius = resultSet.getInt("radius");
+                        autoFarmShortCut = resultSet.getInt("short_cut");
+                        autoFarmHealPercente = resultSet.getInt("heal_percent");
+                        autoFarmBuffProtection = resultSet.getBoolean("buff_protection");
+                        autoAntiKsProtection = resultSet.getBoolean("anti_ks_protection");
+                        autoFarmSummonAttack = resultSet.getBoolean("summon_attack");
+                        autoFarmSummonSkillPercente = resultSet.getInt("summon_skill_percent");
+                        hpPotionPercent = resultSet.getInt("hp_potion_percent");
+                        mpPotionPercent = resultSet.getInt("mp_potion_percent");
+                    } else {
+                        
+                      
+                        autoFarmRadius = 1200;
+                        autoFarmShortCut = 9;
+                        autoFarmHealPercente = 30;
+                        autoFarmBuffProtection = false;
+                        autoAntiKsProtection = false;
+                        autoFarmSummonAttack = false;
+                        autoFarmSummonSkillPercente = 0;
+                        hpPotionPercent = 60;
+                        mpPotionPercent = 60;
+                    }
+                }
+            }
+        } catch (SQLException e) {
+            e.printStackTrace();
+        }
+    }
+	
+	
+	
 }
\ No newline at end of file
diff --git java/com/l2jmega/gameserver/network/SystemMessageId.java java/com/l2jmega/gameserver/network/SystemMessageId.java
index 77a5c12..786f21b 100644
--- java/com/l2jmega/gameserver/network/SystemMessageId.java
+++ java/com/l2jmega/gameserver/network/SystemMessageId.java
@@ -6,6 +6,8 @@
 import java.util.logging.Level;
 import java.util.logging.Logger;
 
+
+
 import com.l2jmega.gameserver.network.serverpackets.SystemMessage;
 
 /**
@@ -11800,6 +11802,21 @@
 	
 	public static final SystemMessageId EVENT;
 	
+	public static final SystemMessageId ACTIVATE_SUMMON_ACTACK;	
+	
+	
+	
+	public static final SystemMessageId WILL_BE_MOVED_INTERFACE;
+	public static final SystemMessageId DESACTIVATE_SUMMON_ACTACK;	
+	public static final SystemMessageId ACTIVATE_RESPECT_HUNT;
+	public static final SystemMessageId DESACTIVATE_RESPECT_HUNT;
+	
+	public static final SystemMessageId AUTO_FARM_DESACTIVATED;
+	public static final SystemMessageId AUTO_FARM_ACTIVATED;
+	
+	
+	
+	
 	/**
 	 * Array containing all SystemMessageIds<br>
 	 * Important: Always initialize with a length of the highest SystemMessageId + 1!!!
@@ -13768,6 +13785,13 @@
 		S1_CANNOT_PARTICIPATE_IN_OLYMPIAD_DURING_TELEPORT = new SystemMessageId(2029);
 		CURRENTLY_LOGGING_IN = new SystemMessageId(2030);
 		PLEASE_WAIT_A_MOMENT = new SystemMessageId(2031);
+		WILL_BE_MOVED_INTERFACE = new SystemMessageId(2154);
+		AUTO_FARM_DESACTIVATED = new SystemMessageId(2155);
+		ACTIVATE_SUMMON_ACTACK = new SystemMessageId(2156);
+		DESACTIVATE_SUMMON_ACTACK = new SystemMessageId(2157);		
+		ACTIVATE_RESPECT_HUNT = new SystemMessageId(2158);
+		DESACTIVATE_RESPECT_HUNT = new SystemMessageId(2159);
+		AUTO_FARM_ACTIVATED = new SystemMessageId(2160);
 		EVENT = new SystemMessageId(2500);
 		
 		buildFastLookupTable();
diff --git java/com/l2jmega/gameserver/network/clientpackets/EnterWorld.java java/com/l2jmega/gameserver/network/clientpackets/EnterWorld.java
index b5006c9..872ac51 100644
--- java/com/l2jmega/gameserver/network/clientpackets/EnterWorld.java
+++ java/com/l2jmega/gameserver/network/clientpackets/EnterWorld.java
@@ -238,6 +238,28 @@
 			}
 		}
 		
+		
+		activeChar.loadAutoFarmSettings();
+		
+	
+		
+		
+		
+		
+		
+		
+		if(activeChar.isSummonAttack()) {
+			activeChar.sendPacket(new SystemMessage(SystemMessageId.ACTIVATE_SUMMON_ACTACK));
+			
+		}
+		
+		if(activeChar.isAntiKsProtected()) {
+			activeChar.sendPacket(new SystemMessage(SystemMessageId.ACTIVATE_RESPECT_HUNT));
+		}
+		
+		
+		
+		
 		// activeChar.sendPacket(SevenSigns.getInstance().getCurrentPeriod().getMessageId());
 		
 		// if player is DE, check for shadow sense skill at night
diff --git java/com/l2jmega/gameserver/network/clientpackets/RequestBypassToServer.java java/com/l2jmega/gameserver/network/clientpackets/RequestBypassToServer.java
index 80e4aae..9564401 100644
--- java/com/l2jmega/gameserver/network/clientpackets/RequestBypassToServer.java
+++ java/com/l2jmega/gameserver/network/clientpackets/RequestBypassToServer.java
@@ -34,6 +34,7 @@
 import com.l2jmega.gameserver.network.serverpackets.ActionFailed;
 import com.l2jmega.gameserver.network.serverpackets.CreatureSay;
 import com.l2jmega.gameserver.network.serverpackets.ExShowScreenMessage;
+import com.l2jmega.gameserver.network.serverpackets.ExShowScreenMessage.SMPOS;
 import com.l2jmega.gameserver.network.serverpackets.HennaInfo;
 import com.l2jmega.gameserver.network.serverpackets.NpcHtmlMessage;
 import com.l2jmega.gameserver.network.serverpackets.PlaySound;
@@ -55,6 +56,8 @@
 import com.l2jmega.commons.lang.StringUtil;
 import com.l2jmega.commons.random.Rnd;
 
+import Base.AutoFarm.AutofarmPlayerRoutine;
+
 public final class RequestBypassToServer extends L2GameClientPacket
 {
 	private static final Logger GMAUDIT_LOG = Logger.getLogger("gmaudit");
@@ -75,10 +78,14 @@
 		if (!FloodProtectors.performAction(getClient(), Action.SERVER_BYPASS) && !_command.startsWith("voiced_getbuff"))
 			return;
 		
+		
 		final Player activeChar = getClient().getActiveChar();
 		if (activeChar == null)
 			return;
 		
+				final AutofarmPlayerRoutine bot = activeChar.getBot();
+				
+		
 		if (_command.isEmpty())
 		{
 			_log.info(activeChar.getName() + " sent an empty requestBypass packet.");
@@ -114,6 +121,106 @@
 				
 				ach.useAdminCommand(_command, activeChar);
 			}

+			else if (_command.startsWith("_infosettings"))
+			{
+				showAutoFarm(activeChar);
+			}
+
+			
+			
+			else if (_command.startsWith("_autofarm"))
+			{
+				if (activeChar.isAutoFarm())
+				{
+					bot.stop();
+					activeChar.setAutoFarm(false);
+				}
+				else
+				{
+					bot.start();
+					activeChar.setAutoFarm(true);
+				}
+				
+			}
+			
+			if (_command.startsWith("_pageAutoFarm"))
+			{
+				StringTokenizer st = new StringTokenizer(_command, " ");
+				st.nextToken();
+				try
+				{
+					String param = st.nextToken();
+					
+					if (param.startsWith("inc_page") || param.startsWith("dec_page"))
+					{
+						int newPage;
+						
+						if (param.startsWith("inc_page"))
+						{
+							newPage = activeChar.getPage() + 1;
+						}
+						else
+						{
+							newPage = activeChar.getPage() - 1;
+						}
+						
+						if (newPage >= 0 && newPage <= 9)
+						{
+							String[] pageStrings =
+							{
+								"F1",
+								"F2",
+								"F3",
+								"F4",
+								"F5",
+								"F6",
+								"F7",
+								"F8",
+								"F9",
+								"F10"
+							};
+							
+							activeChar.setPage(newPage);
+							activeChar.sendPacket(new ExShowScreenMessage("Auto Farm Skill Bar " + pageStrings[newPage], 3 * 1000, SMPOS.TOP_CENTER, false));
+							activeChar.saveAutoFarmSettings();
+							
+						}
+						
+					}
+					
+				}
+				catch (Exception e)
+				{
+					e.printStackTrace();
+				}
+			}
+			
+			if (_command.startsWith("_enableBuffProtect"))
+			{
+				activeChar.setNoBuffProtection(!activeChar.isNoBuffProtected());
+				if (activeChar.isNoBuffProtected())
+				{
+					activeChar.sendPacket(new ExShowScreenMessage("Auto Farm Buff Protect On", 3 * 1000, SMPOS.TOP_CENTER, false));
+				}
+				else
+				{
+					activeChar.sendPacket(new ExShowScreenMessage("Auto Farm Buff Protect Off", 3 * 1000, SMPOS.TOP_CENTER, false));
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			if (_command.startsWith("_enableSummonAttack"))
+			{
+				activeChar.setSummonAttack(!activeChar.isSummonAttack());
+				if (activeChar.isSummonAttack())
+				{
+					activeChar.sendPacket(new SystemMessage(SystemMessageId.ACTIVATE_SUMMON_ACTACK));
+					activeChar.sendPacket(new ExShowScreenMessage("Auto Farm Summon Attack On", 3 * 1000, SMPOS.TOP_CENTER, false));
+				}
+				else
+				{
+					activeChar.sendPacket(new SystemMessage(SystemMessageId.DESACTIVATE_SUMMON_ACTACK));
+					activeChar.sendPacket(new ExShowScreenMessage("Auto Farm Summon Attack Off", 3 * 1000, SMPOS.TOP_CENTER, false));
+				}
+				activeChar.saveAutoFarmSettings();
+			}
+			
+			
+			
 			else if (_command.startsWith("voiced_"))
 			{
 				String command = _command.split(" ")[0];



+	private static final String ACTIVED = "<font color=00FF00>STARTED</font>";
+	private static final String DESATIVED = "<font color=FF0000>STOPPED</font>";
+	private static final String STOP = "STOP";
+	private static final String START = "START";
+	
+	public static void showAutoFarm(Player activeChar)
+	{
+		NpcHtmlMessage html = new NpcHtmlMessage(0);
+		html.setFile("data/html/mods/menu/AutoFarm.htm");
+		html.replace("%player%", activeChar.getName());
+		html.replace("%page%", StringUtil.formatNumber(activeChar.getPage() + 1));
+		html.replace("%heal%", StringUtil.formatNumber(activeChar.getHealPercent()));
+		html.replace("%radius%", StringUtil.formatNumber(activeChar.getRadius()));
+		html.replace("%summonSkill%", StringUtil.formatNumber(activeChar.getSummonSkillPercent()));
+		html.replace("%hpPotion%", StringUtil.formatNumber(activeChar.getHpPotionPercentage()));
+		html.replace("%mpPotion%", StringUtil.formatNumber(activeChar.getMpPotionPercentage()));
+		html.replace("%noBuff%", activeChar.isNoBuffProtected() ? "back=L2UI.CheckBox_checked fore=L2UI.CheckBox_checked" : "back=L2UI.CheckBox fore=L2UI.CheckBox");
+		html.replace("%summonAtk%", activeChar.isSummonAttack() ? "back=L2UI.CheckBox_checked fore=L2UI.CheckBox_checked" : "back=L2UI.CheckBox fore=L2UI.CheckBox");
+		html.replace("%antiKs%", activeChar.isAntiKsProtected() ? "back=L2UI.CheckBox_checked fore=L2UI.CheckBox_checked" : "back=L2UI.CheckBox fore=L2UI.CheckBox");
+		html.replace("%autofarm%", activeChar.isAutoFarm() ? ACTIVED : DESATIVED);
+		html.replace("%button%", activeChar.isAutoFarm() ? STOP : START);
+		activeChar.sendPacket(html);
+	}
+



diff --git java/com/l2jmega/gameserver/network/serverpackets/Die.java java/com/l2jmega/gameserver/network/serverpackets/Die.java
index 10d58c0..889e0fc 100644
--- java/com/l2jmega/gameserver/network/serverpackets/Die.java
+++ java/com/l2jmega/gameserver/network/serverpackets/Die.java
@@ -11,6 +11,10 @@
 import com.l2jmega.gameserver.model.entity.Siege.SiegeSide;
 import com.l2jmega.gameserver.model.pledge.Clan;
 
+import Base.AutoFarm.AutofarmPlayerRoutine;
+
+
+
 public class Die extends L2GameServerPacket
 {
 	private final Creature _activeChar;
@@ -31,9 +35,17 @@
 		if (cha instanceof Player)
 		{
 			Player player = (Player) cha;
+			final AutofarmPlayerRoutine bot = player.getBot();
 			_allowFixedRes = player.getAccessLevel().allowFixedRes();
 			_clan = player.getClan();
 			_canEvent = !(((TvT.is_started() || TvT.is_teleport()) && player._inEventTvT) || ((CTF.is_started() || CTF.is_teleport()) && player._inEventCTF) || cha.isInArenaEvent());
+		
+			
+	    	if (player.isAutoFarm())
+	    	{
+	    		bot.stop();
+	    		player.setAutoFarm(false);
+	    	}
 			
 		}
 		else if (cha instanceof Attackable)

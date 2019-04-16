<?php

require_once ROOT_DIR . '/Action.php';
require_once ROOT_DIR . '/sys/Grouping/MergedGroupedWork.php';
require_once ROOT_DIR . '/services/Admin/ObjectEditor.php';

class Admin_MergedGroupedWorks extends ObjectEditor
{
	function getObjectType(){
		return 'MergedGroupedWork';
	}
	function getToolName(){
		return 'MergedGroupedWorks';
	}
	function getPageTitle(){
		return 'Merged Grouped Works';
	}
	function getAllObjects(){
		$object = new MergedGroupedWork();
		$object->orderBy('sourceGroupedWorkId');
		$object->find();
		$objectList = array();
		while ($object->fetch()){
			$objectList[$object->id] = clone $object;
		}
		return $objectList;
	}
	function getObjectStructure(){
		return MergedGroupedWork::getObjectStructure();
	}
	function getPrimaryKeyColumn(){
		return 'id';
	}
	function getIdKeyColumn(){
		return 'id';
	}
	function getAllowableRoles(){
		return array('opacAdmin', 'cataloging');
	}
	function getInstructions(){
		global $interface;
		return $interface->fetch('Admin/merge_grouped_work_instructions.tpl');
	}
	function getListInstructions(){
		return 'For more information on how to merge grouped works, see the <a href="https://docs.google.com/document/d/13e1lM5kveL_mu8I1iUpVELNW11q2Yi6ZGm0wc9Z3xQE">online documentation</a>.';
	}

}
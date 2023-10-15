package atto.node.election

import atto.node.NotVoterCondition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.context.annotation.Conditional
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@ExperimentalCoroutinesApi
@RestController
@RequestMapping("/elections")
@Conditional(NotVoterCondition::class)
class ElectionController {


}
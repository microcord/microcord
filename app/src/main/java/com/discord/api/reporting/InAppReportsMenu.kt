package com.discord.api.report

sealed class ReportNodeBottomButton(private val type: String) {
    fun a(): String = type

    object Cancel : ReportNodeBottomButton("cancel")
    object Done : ReportNodeBottomButton("done")
    object Submit : ReportNodeBottomButton("submit")

    class Next(private val target: Int) : ReportNodeBottomButton("next") {
        fun b(): Int = target

        override fun equals(other: Any?): Boolean = other is Next && target == other.target
        override fun hashCode(): Int = target
        override fun toString(): String = "Next(target=$target)"
    }
}

class ReportNodeChild(private val name: String, private val ref: Int) {
    fun a(): String = name
    fun b(): Int = ref

    override fun equals(other: Any?): Boolean = other is ReportNodeChild && name == other.name && ref == other.ref
    override fun hashCode(): Int = 31 * name.hashCode() + ref
    override fun toString(): String = "ReportNodeChild(name=$name, ref=$ref)"
}

class ReportNodeElementData(private val elementKey: String, private val elementValue: String) {
    fun a(): String = elementKey
    fun b(): String = elementValue

    override fun equals(other: Any?): Boolean =
        other is ReportNodeElementData && elementKey == other.elementKey && elementValue == other.elementValue

    override fun hashCode(): Int = 31 * elementKey.hashCode() + elementValue.hashCode()
    override fun toString(): String =
        "ReportNodeElementData(elementKey=$elementKey, elementValue=$elementValue)"
}

class NodeElementResult(
    private val key: String,
    private val data: List<ReportNodeElementData>,
) {
    fun a(): List<ReportNodeElementData> = data
    fun b(): String = key

    override fun equals(other: Any?): Boolean = other is NodeElementResult && key == other.key && data == other.data
    override fun hashCode(): Int = 31 * key.hashCode() + data.hashCode()
    override fun toString(): String = "NodeElementResult(key=$key, data=$data)"
}

class ReportNodeElement(
    private val type: String,
    private val name: String,
    private val data: List<ReportNodeElementData>?,
    val should_submit_data: Boolean,
) {
    fun a(): List<ReportNodeElementData>? = data
    fun b(): String = name
    fun c(): String = type

    override fun equals(other: Any?): Boolean =
        other is ReportNodeElement && type == other.type && name == other.name &&
            data == other.data && should_submit_data == other.should_submit_data

    override fun hashCode(): Int = 31 * (31 * (31 * type.hashCode() + name.hashCode()) + (data?.hashCode() ?: 0)) +
        should_submit_data.hashCode()

    override fun toString(): String =
        "ReportNodeElement(type=$type, name=$name, data=$data, should_submit_data=$should_submit_data)"
}

class ReportNode(
    private val id: Int,
    val menu_name: String?,
    private val header: String,
    private val subheader: String?,
    private val info: String?,
    val description: String?,
    private val children: List<ReportNodeChild>,
    private val elements: List<ReportNodeElement>,
    private val button: ReportNodeBottomButton?,
) {
    fun a(): ReportNodeBottomButton? = button
    fun b(): List<ReportNodeChild> = children
    fun c(): List<ReportNodeElement> = elements
    fun d(): String = header
    fun e(): Int = id
    fun f(): String? = info
    fun g(): String? = subheader

    override fun equals(other: Any?): Boolean =
        other is ReportNode && id == other.id && menu_name == other.menu_name && header == other.header &&
            subheader == other.subheader && info == other.info && description == other.description &&
            children == other.children && elements == other.elements && button == other.button

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (menu_name?.hashCode() ?: 0)
        result = 31 * result + header.hashCode()
        result = 31 * result + (subheader?.hashCode() ?: 0)
        result = 31 * result + (info?.hashCode() ?: 0)
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + children.hashCode()
        result = 31 * result + elements.hashCode()
        return 31 * result + (button?.hashCode() ?: 0)
    }

    override fun toString(): String =
        "ReportNode(id=$id, menu_name=$menu_name, header=$header, subheader=$subheader, info=$info, " +
            "description=$description, children=$children, elements=$elements, button=$button)"
}

class NodeResult(
    private val node: ReportNode,
    private val destination: ReportNodeChild?,
    private val elementResult: NodeElementResult?,
) {
    fun a(): ReportNodeChild? = destination
    fun b(): NodeElementResult? = elementResult
    fun c(): ReportNode = node

    override fun equals(other: Any?): Boolean =
        other is NodeResult && node == other.node && destination == other.destination && elementResult == other.elementResult

    override fun hashCode(): Int = 31 * (31 * node.hashCode() + (destination?.hashCode() ?: 0)) +
        (elementResult?.hashCode() ?: 0)

    override fun toString(): String =
        "NodeResult(node=$node, destination=$destination, elementResult=$elementResult)"
}

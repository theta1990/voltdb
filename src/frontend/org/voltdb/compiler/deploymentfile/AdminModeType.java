//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, vJAXB 2.1.10 in JDK 6 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2011.03.07 at 03:55:12 PM EST 
//


package org.voltdb.compiler.deploymentfile;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for adminModeType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="adminModeType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;attribute name="port" use="required" type="{http://www.w3.org/2001/XMLSchema}int" />
 *       &lt;attribute name="adminstartup" type="{http://www.w3.org/2001/XMLSchema}boolean" default="false" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "adminModeType")
public class AdminModeType {

    @XmlAttribute(required = true)
    protected int port;
    @XmlAttribute
    protected Boolean adminstartup;

    /**
     * Gets the value of the port property.
     * 
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the value of the port property.
     * 
     */
    public void setPort(int value) {
        this.port = value;
    }

    /**
     * Gets the value of the adminstartup property.
     * 
     * @return
     *     possible object is
     *     {@link Boolean }
     *     
     */
    public boolean isAdminstartup() {
        if (adminstartup == null) {
            return false;
        } else {
            return adminstartup;
        }
    }

    /**
     * Sets the value of the adminstartup property.
     * 
     * @param value
     *     allowed object is
     *     {@link Boolean }
     *     
     */
    public void setAdminstartup(Boolean value) {
        this.adminstartup = value;
    }

}
